/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.hive

import java.util

import scala.collection.mutable
import scala.util.control.NonFatal

import com.pivotal.gemfirexd.internal.engine.diag.HiveTablesVTI
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.ql.metadata.HiveException
import org.apache.thrift.TException

import org.apache.spark.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils._
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.{And, AttributeReference, BoundReference, Expression, InterpretedPredicate}
import org.apache.spark.sql.catalyst.plans.logical.{ColumnStat, Statistics}
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.datasources.PartitioningUtils
import org.apache.spark.sql.hive.client.HiveClient
import org.apache.spark.sql.types.StructType

private[spark] class SnappyExternalCatalog(var client: HiveClient, hadoopConf: Configuration)
    extends ExternalCatalog with Logging {

  import CatalogTypes.TablePartitionSpec

  // Exceptions thrown by the hive client that we would like to wrap
  private val clientExceptions = Set(
    classOf[HiveException].getCanonicalName,
    classOf[TException].getCanonicalName)

  /**
   * Whether this is an exception thrown by the hive client that should be wrapped.
   *
   * Due to classloader isolation issues, pattern matching won't work here so we need
   * to compare the canonical names of the exceptions, which we assume to be stable.
   */
  private def isClientException(e: Throwable): Boolean = {
    var temp: Class[_] = e.getClass
    var found = false
    while (temp != null && !found) {
      found = clientExceptions.contains(temp.getCanonicalName)
      temp = temp.getSuperclass
    }
    found
  }

  private def isDisconnectException(t: Throwable): Boolean = {
    if (t != null) {
      val tClass = t.getClass.getName
      tClass.contains("DisconnectedException") ||
          tClass.contains("DisconnectException") ||
          (tClass.contains("MetaException") && t.getMessage.contains("retries")) ||
          isDisconnectException(t.getCause)
    } else {
      false
    }
  }

  def withHiveExceptionHandling[T](function: => T): T = synchronized {
    val oldFlag = HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.get
    if (oldFlag ne java.lang.Boolean.TRUE) {
      HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.set(java.lang.Boolean.TRUE)
    }
    try {
      function
    } catch {
      case he: HiveException if isDisconnectException(he) =>
        // stale JDBC connection
        SnappyStoreHiveCatalog.closeHive(client)
        SnappyStoreHiveCatalog.suspendActiveSession {
          client = client.newSession()
        }
        function
    } finally {
      if (oldFlag ne java.lang.Boolean.TRUE) {
        HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.set(oldFlag)
      }
    }
  }

  /**
   * Run some code involving `client` in a [[synchronized]] block and wrap certain
   * exceptions thrown in the process in [[AnalysisException]].
   */
  private def withClient[T](body: => T): T = synchronized {
    try {
      body
    } catch {
      case NonFatal(e) if isClientException(e) =>
        throw new AnalysisException(
          e.getClass.getCanonicalName + ": " + e.getMessage, cause = Some(e))
    }
  }

  override protected def requireDbExists(db: String): Unit = {
    if (!databaseExists(db)) {
      throw new AnalysisException(s"Schema '$db' not found")
    }
  }

  private def requireDbMatches(db: String, table: CatalogTable): Unit = {
    if (!table.identifier.database.contains(db)) {
      throw new AnalysisException(
        s"Provided database '$db' does not match the one specified in the " +
            s"table definition (${table.identifier.database.getOrElse("n/a")})")
    }
  }

  override def requireTableExists(db: String, table: String): Unit = {
    withClient {
      getTable(db, table)
    }
  }

  // --------------------------------------------------------------------------
  // Databases
  // --------------------------------------------------------------------------

  override def createDatabase(
      dbDefinition: CatalogDatabase,
      ignoreIfExists: Boolean): Unit = withClient {
    withHiveExceptionHandling(client.createDatabase(dbDefinition, ignoreIfExists))
  }

  override def dropDatabase(
      db: String,
      ignoreIfNotExists: Boolean,
      cascade: Boolean): Unit = withClient {
    withHiveExceptionHandling(client.dropDatabase(db, ignoreIfNotExists, cascade))
  }

  /**
   * Alter a database whose name matches the one specified in `dbDefinition`,
   * assuming the database exists.
   *
   * Note: As of now, this only supports altering database properties!
   */
  override def alterDatabase(dbDefinition: CatalogDatabase): Unit = withClient {
    val existingDb = getDatabase(dbDefinition.name)
    if (existingDb.properties == dbDefinition.properties) {
      logWarning(s"Request to alter database ${dbDefinition.name} is a no-op because " +
          s"the provided database properties are the same as the old ones. Hive does not " +
          s"currently support altering other database fields.")
    }
    withHiveExceptionHandling(client.alterDatabase(dbDefinition))
  }

  override def getDatabase(db: String): CatalogDatabase = withClient {
    withHiveExceptionHandling(client.getDatabase(db))
  }

  override def databaseExists(db: String): Boolean = withClient {
    withHiveExceptionHandling(SnappyStoreHiveCatalog.getDatabaseOption(client, db).isDefined)
  }

  override def listDatabases(): Seq[String] = withClient {
    withHiveExceptionHandling(client.listDatabases("*"))
  }

  override def listDatabases(pattern: String): Seq[String] = withClient {
    withHiveExceptionHandling(client.listDatabases(pattern))
  }

  override def setCurrentDatabase(db: String): Unit = withClient {
    withHiveExceptionHandling(client.setCurrentDatabase(db))
  }

  // --------------------------------------------------------------------------
  // Tables
  // --------------------------------------------------------------------------

  override def createTable(
      tableDefinition: CatalogTable,
      ignoreIfExists: Boolean): Unit = withClient {
    requireDbExists(tableDefinition.database)
    requireDbMatches(tableDefinition.database, tableDefinition)

    if (
    // If this is an external data source table...
      tableDefinition.properties.contains("spark.sql.sources.provider") &&
          tableDefinition.tableType == CatalogTableType.EXTERNAL &&
          // ... that is not persisted as Hive compatible format (external tables in Hive compatible
          // format always set `locationUri` to the actual data location and should NOT be hacked as
          // following.)
          tableDefinition.storage.locationUri.isEmpty
    ) {
      // !! HACK ALERT !!
      //
      // Due to a restriction of Hive metastore, here we have to set `locationUri` to a temporary
      // directory that doesn't exist yet but can definitely be successfully created, and then
      // delete it right after creating the external data source table. This location will be
      // persisted to Hive metastore as standard Hive table location URI, but Spark SQL doesn't
      // really use it. Also, since we only do this workaround for external tables, deleting the
      // directory after the fact doesn't do any harm.
      //
      // Please refer to https://issues.apache.org/jira/browse/SPARK-15269 for more details.
      val tempPath = {
        val dbLocation = getDatabase(tableDefinition.database).locationUri
        new Path(dbLocation, tableDefinition.identifier.table + "-__PLACEHOLDER__")
      }

      try {
        withHiveExceptionHandling(client.createTable(
          tableDefinition.withNewStorage(locationUri = Some(tempPath.toString)),
          ignoreIfExists))
      } finally {
        FileSystem.get(tempPath.toUri, hadoopConf).delete(tempPath, true)
      }
    } else {
      withHiveExceptionHandling(client.createTable(tableDefinition, ignoreIfExists))
    }
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(tableDefinition)
  }

  override def dropTable(
      db: String,
      table: String,
      ignoreIfNotExists: Boolean,
      purge: Boolean): Unit = withClient {
    requireDbExists(db)
    withHiveExceptionHandling(client.dropTable(db, table, ignoreIfNotExists, purge))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, table)
  }

  override def renameTable(db: String, oldName: String, newName: String): Unit = withClient {
    val newTable = withHiveExceptionHandling(client.getTable(db, oldName))
        .copy(identifier = TableIdentifier(newName, Some(db)))
    withHiveExceptionHandling(client.alterTable(oldName, newTable))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, oldName)
  }

  /**
   * Alter a table whose name that matches the one specified in `tableDefinition`,
   * assuming the table exists.
   *
   * Note: As of now, this only supports altering table properties, serde properties,
   * and num buckets!
   */
  override def alterTable(tableDefinition: CatalogTable): Unit = withClient {
    requireDbMatches(tableDefinition.database, tableDefinition)
    requireTableExists(tableDefinition.database, tableDefinition.identifier.table)
    withHiveExceptionHandling(client.alterTable(tableDefinition))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(tableDefinition)
  }

  def alterTableSchema(db: String, table: String, schema: StructType): Unit = withClient {
    requireTableExists(db, table)
    val hiveTable = withHiveExceptionHandling(client.getTable(db, table))
    val updatedTable = hiveTable.copy(schema = schema)
    try {
      withHiveExceptionHandling(client.alterTable(updatedTable))
    } catch {
      case NonFatal(e) =>
        val warningMessage =
          s"Could not alter schema of table  ${hiveTable.identifier.quotedString} in a Hive " +
              "compatible way. Updating Hive metastore in Spark SQL specific format."
        logWarning(warningMessage, e)
        withHiveExceptionHandling(client.alterTable(
          updatedTable.copy(schema = updatedTable.partitionSchema)))
    }
  }

  override def getTable(db: String, table: String): CatalogTable = {
    getTableOption(db, table) match {
      case Some(catalogTable) => catalogTable
      case None => throw new NoSuchTableException(db, table)
    }
  }

  override def getTableOption(db: String, table: String): Option[CatalogTable] = withClient {
    withHiveExceptionHandling {
      val catalogTableOpt = client.getTableOption(db, table)
      if (catalogTableOpt.isEmpty) return None
      val catalogTable = catalogTableOpt.get
      // TODO: remove the type check and return for all types for consistency
      if (catalogTable.tableType == CatalogTableType.VIEW) {
        // update the meta-data from properties
        ExternalStoreUtils.getTableSchema(catalogTable.properties) match {
          case Some(schema) => Some(catalogTable.copy(schema = schema))
          case None => catalogTableOpt
        }
      } else catalogTableOpt
    }
  }

  override def tableExists(db: String, table: String): Boolean = withClient {
    withHiveExceptionHandling(client.getTableOption(db, table).isDefined)
  }

  override def listTables(db: String): Seq[String] = withClient {
    withHiveExceptionHandling(client.listTables(db))
  }

  override def listTables(db: String, pattern: String): Seq[String] = withClient {
    withHiveExceptionHandling(client.listTables(db, pattern))
  }

  override def loadTable(
      db: String,
      table: String,
      loadPath: String,
      isOverwrite: Boolean,
      holdDDLTime: Boolean): Unit = withClient {
    requireTableExists(db, table)
    withHiveExceptionHandling(client.loadTable(
      loadPath,
      s"$db.$table",
      isOverwrite,
      holdDDLTime))
  }

  override def loadPartition(
      db: String,
      table: String,
      loadPath: String,
      partition: TablePartitionSpec,
      isOverwrite: Boolean,
      holdDDLTime: Boolean,
      inheritTableSpecs: Boolean): Unit = withClient {
    requireTableExists(db, table)

    val orderedPartitionSpec = new util.LinkedHashMap[String, String]()
    getTable(db, table).partitionColumnNames.foreach { colName =>
      orderedPartitionSpec.put(colName, partition(colName))
    }

    withHiveExceptionHandling(client.loadPartition(
      loadPath,
      s"$db",
      s".$table",
      orderedPartitionSpec,
      isOverwrite,
      holdDDLTime,
      inheritTableSpecs))
  }

  // --------------------------------------------------------------------------
  // Partitions
  // --------------------------------------------------------------------------

  val SPARK_SQL_PREFIX: String = "spark.sql."

  val DATASOURCE_PREFIX: String = SPARK_SQL_PREFIX + "sources."
  val DATASOURCE_PROVIDER: String = DATASOURCE_PREFIX + "provider"
  val DATASOURCE_SCHEMA: String = DATASOURCE_PREFIX + "schema"
  val DATASOURCE_SCHEMA_PREFIX: String = DATASOURCE_SCHEMA + "."
  val DATASOURCE_SCHEMA_NUMPARTS: String = DATASOURCE_SCHEMA_PREFIX + "numParts"
  val DATASOURCE_SCHEMA_NUMPARTCOLS: String = DATASOURCE_SCHEMA_PREFIX + "numPartCols"
  val DATASOURCE_SCHEMA_NUMSORTCOLS: String = DATASOURCE_SCHEMA_PREFIX + "numSortCols"
  val DATASOURCE_SCHEMA_NUMBUCKETS: String = DATASOURCE_SCHEMA_PREFIX + "numBuckets"
  val DATASOURCE_SCHEMA_NUMBUCKETCOLS: String = DATASOURCE_SCHEMA_PREFIX + "numBucketCols"
  val DATASOURCE_SCHEMA_PART_PREFIX: String = DATASOURCE_SCHEMA_PREFIX + "part."
  val DATASOURCE_SCHEMA_PARTCOL_PREFIX: String = DATASOURCE_SCHEMA_PREFIX + "partCol."
  val DATASOURCE_SCHEMA_BUCKETCOL_PREFIX: String = DATASOURCE_SCHEMA_PREFIX + "bucketCol."
  val DATASOURCE_SCHEMA_SORTCOL_PREFIX: String = DATASOURCE_SCHEMA_PREFIX + "sortCol."

  val STATISTICS_PREFIX: String = SPARK_SQL_PREFIX + "statistics."
  val STATISTICS_TOTAL_SIZE: String = STATISTICS_PREFIX + "totalSize"
  val STATISTICS_NUM_ROWS: String = STATISTICS_PREFIX + "numRows"
  val STATISTICS_COL_STATS_PREFIX: String = STATISTICS_PREFIX + "colStats."

  val TABLE_PARTITION_PROVIDER: String = SPARK_SQL_PREFIX + "partitionProvider"
  val TABLE_PARTITION_PROVIDER_CATALOG: String = "catalog"
  val TABLE_PARTITION_PROVIDER_FILESYSTEM: String = "filesystem"

  /**
    * Returns the fully qualified name used in table properties for a particular column stat.
    * For example, for column "mycol", and "min" stat, this should return
    * "spark.sql.statistics.colStats.mycol.min".
    */
  private def columnStatKeyPropName(columnName: String, statKey: String): String = {
    STATISTICS_COL_STATS_PREFIX + columnName + "." + statKey
  }

  // Hive metastore is not case preserving and the partition columns are always lower cased. We need
  // to lower case the column names in partition specification before calling partition related Hive
  // APIs, to match this behaviour.
  private def lowerCasePartitionSpec(spec: TablePartitionSpec): TablePartitionSpec = {
    spec.map { case (k, v) => k.toLowerCase -> v }
  }

  // Build a map from lower-cased partition column names to exact column names for a given table
  private def buildLowerCasePartColNameMap(table: CatalogTable): Map[String, String] = {
    val actualPartColNames = table.partitionColumnNames
    actualPartColNames.map(colName => (colName.toLowerCase, colName)).toMap
  }

  // Hive metastore is not case preserving and the column names of the partition specification we
  // get from the metastore are always lower cased. We should restore them w.r.t. the actual table
  // partition columns.
  private def restorePartitionSpec(
      spec: TablePartitionSpec,
      partColMap: Map[String, String]): TablePartitionSpec = {
    spec.map { case (k, v) => partColMap(k.toLowerCase) -> v }
  }

  private def restorePartitionSpec(
      spec: TablePartitionSpec,
      partCols: Seq[String]): TablePartitionSpec = {
    spec.map { case (k, v) => partCols.find(_.equalsIgnoreCase(k)).get -> v }
  }

  /**
    * Restores table metadata from the table properties if it's a datasouce table. This method is
    * kind of a opposite version of [[createTable]].
    *
    * It reads table schema, provider, partition column names and bucket specification from table
    * properties, and filter out these special entries from table properties.
    */
  private def restoreTableMetadata(inputTable: CatalogTable): CatalogTable = {
    var table = inputTable
    // construct Spark's statistics from information in Hive metastore
    val statsProps = table.properties.filterKeys(_.startsWith(STATISTICS_PREFIX))

    if (statsProps.nonEmpty) {
      val colStats = new mutable.HashMap[String, ColumnStat]

      // For each column, recover its column stats. Note that this is currently a O(n^2) operation,
      // but given the number of columns it usually not enormous, this is probably OK as a start.
      // If we want to map this a linear operation, we'd need a stronger contract between the
      // naming convention used for serialization.
      table.schema.foreach { field =>
        if (statsProps.contains(columnStatKeyPropName(field.name, ColumnStat.KEY_VERSION))) {
          // If "version" field is defined, then the column stat is defined.
          val keyPrefix = columnStatKeyPropName(field.name, "")
          val colStatMap = statsProps.filterKeys(_.startsWith(keyPrefix)).map { case (k, v) =>
            (k.drop(keyPrefix.length), v)
          }

          ColumnStat.fromMap(table.identifier.table, field, colStatMap).foreach {
            colStat => colStats += field.name -> colStat
          }
        }
      }

      table = table.copy(
        stats = Some(Statistics(
          sizeInBytes = BigInt(table.properties(STATISTICS_TOTAL_SIZE)),
          rowCount = table.properties.get(STATISTICS_NUM_ROWS).map(BigInt(_)),
          colStats = colStats.toMap)))
    }

    // Get the original table properties as defined by the user.
    table.copy(
      properties = table.properties.filterNot { case (key, _) => key.startsWith(SPARK_SQL_PREFIX) })
  }

  override def loadDynamicPartitions(
      db: String,
      table: String,
      loadPath: String,
      partition: TablePartitionSpec,
      replace: Boolean,
      numDP: Int,
      holdDDLTime: Boolean): Unit = {
    requireTableExists(db, table)

    val orderedPartitionSpec = new util.LinkedHashMap[String, String]()
    getTable(db, table).partitionColumnNames.foreach { colName =>
      orderedPartitionSpec.put(colName.toLowerCase, partition(colName))
    }

    withHiveExceptionHandling(client.loadDynamicPartitions(
      loadPath,
      db,
      table,
      orderedPartitionSpec,
      replace,
      numDP,
      holdDDLTime))
  }

  override def getPartitionOption(
      db: String,
      table: String,
      spec: TablePartitionSpec): Option[CatalogTablePartition] = {
    withHiveExceptionHandling(client.getPartitionOption(db, table,
      lowerCasePartitionSpec(spec))).map { part =>
      part.copy(spec = restorePartitionSpec(part.spec, getTable(db, table).partitionColumnNames))
    }
  }

  override def listPartitionNames(
      db: String,
      table: String,
      partialSpec: Option[TablePartitionSpec]): Seq[String] = {
    val catalogTable = getTable(db, table)
    val partColNameMap = buildLowerCasePartColNameMap(catalogTable).mapValues(escapePathName)
    val clientPartitionNames =
      withHiveExceptionHandling(client.getPartitionNames(catalogTable,
        partialSpec.map(lowerCasePartitionSpec)))
    clientPartitionNames.map { partName =>
      val partSpec = PartitioningUtils.parsePathFragmentAsSeq(partName)
      partSpec.map { case (part, partValue) =>
        partColNameMap(part.toLowerCase) + "=" + escapePathName(partValue)
      }.mkString("/")
    }
  }

  override def listPartitionsByFilter(
      db: String,
      table: String,
      predicates: Seq[Expression]): Seq[CatalogTablePartition] = withClient {
    val rawTable = withHiveExceptionHandling(client.getTable(db, table))
    val catalogTable = restoreTableMetadata(rawTable)
    val partitionColumnNames = catalogTable.partitionColumnNames.toSet
    val nonPartitionPruningPredicates = predicates.filterNot {
      _.references.map(_.name).toSet.subsetOf(partitionColumnNames)
    }

    if (nonPartitionPruningPredicates.nonEmpty) {
      sys.error("Expected only partition pruning predicates: " +
          predicates.reduceLeft(And))
    }

    val partitionSchema = catalogTable.partitionSchema
    val partColNameMap = buildLowerCasePartColNameMap(getTable(db, table))

    if (predicates.nonEmpty) {
      val clientPrunedPartitions = withHiveExceptionHandling(client.getPartitionsByFilter(
        rawTable, predicates)).map { part =>
        part.copy(spec = restorePartitionSpec(part.spec, partColNameMap))
      }
      val boundPredicate =
        InterpretedPredicate.create(predicates.reduce(And).transform {
          case att: AttributeReference =>
            val index = partitionSchema.indexWhere(_.name == att.name)
            BoundReference(index, partitionSchema(index).dataType, nullable = true)
        })
      clientPrunedPartitions.filter { p => boundPredicate(p.toRow(partitionSchema)) }
    } else {
      withHiveExceptionHandling(client.getPartitions(catalogTable)).map { part =>
        part.copy(spec = restorePartitionSpec(part.spec, partColNameMap))
      }
    }
  }

  override def createPartitions(
      db: String,
      table: String,
      parts: Seq[CatalogTablePartition],
      ignoreIfExists: Boolean): Unit = withClient {
    requireTableExists(db, table)
    withHiveExceptionHandling(client.createPartitions(db, table, parts, ignoreIfExists))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, table)
  }

  override def dropPartitions(
      db: String,
      table: String,
      parts: Seq[TablePartitionSpec],
      ignoreIfNotExists: Boolean,
      purge: Boolean,
      retainData: Boolean): Unit = withClient {
    requireTableExists(db, table)
    withHiveExceptionHandling(client.dropPartitions(
      db, table, parts, ignoreIfNotExists, purge = false, retainData = false))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, table)
  }

  override def renamePartitions(
      db: String,
      table: String,
      specs: Seq[TablePartitionSpec],
      newSpecs: Seq[TablePartitionSpec]): Unit = withClient {
    withHiveExceptionHandling(client.renamePartitions(db, table, specs, newSpecs))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, table)
  }

  override def alterPartitions(
      db: String,
      table: String,
      newParts: Seq[CatalogTablePartition]): Unit = withClient {
    withHiveExceptionHandling(client.alterPartitions(db, table, newParts))
    SnappyStoreHiveCatalog.refreshSchemaOnAllMembers(db, table)
  }

  override def getPartition(
      db: String,
      table: String,
      spec: TablePartitionSpec): CatalogTablePartition = withClient {
    withHiveExceptionHandling(client.getPartition(db, table, spec))
  }

  /**
   * Returns the partition names from hive metastore for a given table in a database.
   */
  override def listPartitions(
      db: String,
      table: String,
      partialSpec: Option[TablePartitionSpec] = None): Seq[CatalogTablePartition] = withClient {
    withHiveExceptionHandling(client.getPartitions(db, table, partialSpec))
  }

  // --------------------------------------------------------------------------
  // Functions
  // --------------------------------------------------------------------------

  override def createFunction(
      db: String,
      funcDefinition: CatalogFunction): Unit = withClient {
    // Hive's metastore is case insensitive. However, Hive's createFunction does
    // not normalize the function name (unlike the getFunction part). So,
    // we are normalizing the function name.
    val functionName = funcDefinition.identifier.funcName.toLowerCase
    val functionIdentifier = funcDefinition.identifier.copy(funcName = functionName)
    withHiveExceptionHandling(client.createFunction(db,
      funcDefinition.copy(identifier = functionIdentifier)))
    SnappySession.clearAllCache()
  }

  override def dropFunction(db: String, name: String): Unit = withClient {
    withHiveExceptionHandling(client.dropFunction(db, name))
    SnappySession.clearAllCache()
  }

  override def renameFunction(db: String, oldName: String, newName: String): Unit = withClient {
    withHiveExceptionHandling(client.renameFunction(db, oldName, newName))
    SnappySession.clearAllCache()
  }

  override def getFunction(db: String, funcName: String): CatalogFunction = withClient {
    withHiveExceptionHandling(client.getFunction(db, funcName))
  }

  override def functionExists(db: String, funcName: String): Boolean = withClient {
    withHiveExceptionHandling(client.functionExists(db, funcName))
  }

  override def listFunctions(db: String, pattern: String): Seq[String] = withClient {
    withHiveExceptionHandling(client.listFunctions(db, pattern))
  }

  def close(): Unit = synchronized {
    SnappyStoreHiveCatalog.closeHive(client)
  }
}
