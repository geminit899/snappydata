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

package org.apache.spark.sql.streaming

import java.sql.SQLException
import java.util.NoSuchElementException

import io.snappydata.Property._
import io.snappydata.StreamingConstants._
import org.apache.log4j.Logger

import org.apache.spark.sql.execution.streaming.Sink
import org.apache.spark.sql.sources.{DataSourceRegister, StreamSinkProvider}
import org.apache.spark.sql.streaming.DefaultSnappySinkCallback.{TEST_FAILBATCH_OPTION, log}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, Row, SnappySession, _}
import org.apache.spark.util.Utils

/**
 * Should be implemented by clients who wants to override default behavior provided by
 * [[DefaultSnappySinkCallback]].
 * <p>
 * In order to override the default callback behavior the qualified name of the implementing
 * class needs to be passed against `sinkCallback` option while defining stream query.
 *
 */
trait SnappySinkCallback {

  /**
   * This method is called for each streaming batch after checking the possibility of batch
   * duplication which is indicated by `possibleDuplicate` flag.
   * <p>
   * A duplicate batch might be picked up for processing in case of failure. In case of batch
   * duplication, this method should handle batch in idempotent manner in order to avoid
   * data inconsistency.
   */
  def process(snappySession: SnappySession, sinkProps: Map[String, String],
      batchId: Long, df: Dataset[Row], possibleDuplicate: Boolean = false): Unit
}

class SnappyStoreSinkProvider extends StreamSinkProvider with DataSourceRegister {

  @Override
  def createSink(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      partitionColumns: Seq[String],
      outputMode: OutputMode): Sink = {
    createSinkStateTableIfNotExist(sqlContext)
    val cc = try {
      Utils.classForName(parameters(SINK_CALLBACK)).newInstance()
    } catch {
      case _: NoSuchElementException => new DefaultSnappySinkCallback()
    }

    SnappyStoreSink(sqlContext.asInstanceOf[SnappyContext].snappySession, parameters,
      cc.asInstanceOf[SnappySinkCallback])
  }

  private def createSinkStateTableIfNotExist(sqlContext: SQLContext) = {
    sqlContext.asInstanceOf[SnappyContext].snappySession.sql(s"create table if not exists" +
        s" $SINK_STATE_TABLE (" +
        " stream_query_id varchar(200)," +
        " batch_id long, " +
        " PRIMARY KEY (stream_query_id)) using row options(DISKSTORE 'GFXD-DD-DISKSTORE')")
  }

  @Override
  def shortName(): String = SNAPPY_SINK_NAME

}

case class SnappyStoreSink(snappySession: SnappySession,
    parameters: Map[String, String], sinkCallback: SnappySinkCallback) extends Sink {

  override def addBatch(batchId: Long, data: Dataset[Row]): Unit = {
    val streamQueryId = snappySession.sessionCatalog.formatName(parameters(STREAM_QUERY_ID))

    val updated = snappySession.sql(s"update $SINK_STATE_TABLE " +
        s"set batch_id=$batchId where stream_query_id='$streamQueryId' and batch_id != $batchId")
        .collect()(0).getAs("count").asInstanceOf[Long]

    // TODO: use JDBC connection here
    var posDup = false

    if (updated == 0) {
      try {
        snappySession.insert(SINK_STATE_TABLE, Row(streamQueryId, batchId))
        posDup = false
      }
      catch {
        case e: SQLException if e.getSQLState.equals("23505") => posDup = true
      }
    }

    val hashAggregateSizeChanged = HashAggregateSize.get(snappySession.sessionState.conf)
        .equals(HashAggregateSize.defaultValue.get)
    if (hashAggregateSizeChanged) {
      HashAggregateSize.set(snappySession.sessionState.conf, "10m")
    }
    try {
      sinkCallback.process(snappySession, parameters, batchId, convert(data), posDup)
    } finally {
      if (hashAggregateSizeChanged) {
        HashAggregateSize.set(snappySession.sessionState.conf, HashAggregateSize.defaultValue.get)
      }
    }
  }

  /**
   * This conversion is necessary as Sink
   * documentation disallows an operation on incoming dataframe.
   * Otherwise it will break incremental planning of streaming dataframes.
   * See http://apache-spark-developers-list.1001551.n3.nabble.com/
   * Structured-Streaming-Sink-in-2-0-collect-foreach-restrictions-added-in-
   * SPARK-16020-td18118.html
   * for a detailed discussion.
   */
  def convert(ds: DataFrame): DataFrame = {
    snappySession.internalCreateDataFrame(
      ds.queryExecution.toRdd,
      StructType(ds.schema.fields))
  }
}

object DefaultSnappySinkCallback {
  private val log = Logger.getLogger(classOf[DefaultSnappySinkCallback].getName)
  private val TEST_FAILBATCH_OPTION = "internal___failBatch"
}

import org.apache.spark.sql.snappy._

class DefaultSnappySinkCallback extends SnappySinkCallback {
  def process(snappySession: SnappySession, parameters: Map[String, String],
      batchId: Long, df: Dataset[Row], posDup: Boolean) {
    df.cache().count()
    log.debug(s"Processing batchId $batchId with parameters $parameters ...")
    val tableName = snappySession.sessionCatalog.formatName(parameters(TABLE_NAME))
    val conflationEnabled = if (parameters.contains(CONFLATION)) {
      parameters(CONFLATION).toBoolean
    } else {
      false
    }
    val keyColumns = snappySession.sessionCatalog.getKeyColumnsAndPositions(tableName)
    val eventTypeColumnAvailable = df.schema.map(_.name).contains(EVENT_TYPE_COLUMN)

    log.debug(s"keycolumns: '${keyColumns.map(p => s"${p._1.name}(${p._2})").mkString(",")}'" +
        s", eventTypeColumnAvailable:$eventTypeColumnAvailable,possible duplicate: $posDup")

    if (keyColumns.nonEmpty) {
      val dataFrame: DataFrame = if (conflationEnabled) getConflatedDf else df
      if (eventTypeColumnAvailable) {
        val deleteDf = dataFrame.filter(dataFrame(EVENT_TYPE_COLUMN) === EventType.DELETE)
            .drop(EVENT_TYPE_COLUMN)
        deleteDf.write.deleteFrom(tableName)
        if (posDup) {
          val upsertEventTypes = List(EventType.INSERT, EventType.UPDATE)
          val upsertDf = dataFrame
              .filter(dataFrame(EVENT_TYPE_COLUMN).isin(upsertEventTypes: _*))
              .drop(EVENT_TYPE_COLUMN)
          upsertDf.write.putInto(tableName)
        } else {
          val insertDf = dataFrame.filter(dataFrame(EVENT_TYPE_COLUMN) === EventType.INSERT)
              .drop(EVENT_TYPE_COLUMN)
          insertDf.write.insertInto(tableName)
          val updateDf = dataFrame.filter(dataFrame(EVENT_TYPE_COLUMN) === EventType.UPDATE)
              .drop(EVENT_TYPE_COLUMN)
          updateDf.write.putInto(tableName)
        }
      } else {
        dataFrame.write.putInto(tableName)
      }
    }
    else {
      if (eventTypeColumnAvailable) {
        val msg = s"$EVENT_TYPE_COLUMN is present in data but key columns are not defined on table."
        throw new IllegalStateException(msg)
      } else {
        df.write.insertInto(tableName)
      }
    }
    // test hook for validating idempotency
    if (parameters.contains(TEST_FAILBATCH_OPTION)
        && parameters(TEST_FAILBATCH_OPTION) == "true") {
      throw new RuntimeException("dummy failure for test")
    }

    log.debug(s"Processing batchId $batchId with parameters $parameters ... Done.")


    // We are grouping by key columns and getting the last record.
    // Note that this approach will work as far as the incoming dataframe is partitioned
    // by key columns and events are available in the correct order in the respective partition.
    // If above conditions are not met in that case we will need separate ordering column(s) to
    // order the events. A new optional parameter needs to be exposed as part of the snappysink
    // API to accept the ordering column(s).
    def getConflatedDf = {
      import org.apache.spark.sql.functions._
      val keyColumnPositions = keyColumns.map(_._2)
      var index = 0
      val (keyCols, otherCols) = df.columns.toList.partition { _ =>
        val contains = keyColumnPositions.contains(index)
        index += 1
        contains
      }
      val conflatedDf = if (otherCols.isEmpty) df.distinct()
      else {
        val exprs = otherCols.map(c => last(c).alias(c))
        df.groupBy(keyCols.head, keyCols.tail: _*)
            .agg(exprs.head, exprs.tail: _*)
            .select(df.columns.head, df.columns.tail: _*)
      }
      conflatedDf.cache()
    }
  }
}
