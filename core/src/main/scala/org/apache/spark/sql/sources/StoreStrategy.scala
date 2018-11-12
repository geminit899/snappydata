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
package org.apache.spark.sql.sources

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{InsertIntoTable, LogicalPlan, OverwriteOptions}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.command.{ExecutedCommandExec, RunnableCommand}
import org.apache.spark.sql.execution.datasources.{CreateTable, LogicalRelation}
import org.apache.spark.sql.internal.{BypassRowLevelSecurity, PutIntoColumnTable}
import org.apache.spark.sql.types.{DataType, LongType, StructType}
import org.apache.spark.sql.{Strategy, _}

/**
 * Support for DML and other operations on external tables.
 */
object StoreStrategy extends Strategy {
  def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {

    case CreateTable(tableDesc, mode, None) =>
      val userSpecifiedSchema: Option[StructType] = if (tableDesc.schema.isEmpty) None
      else {
        Some(SparkSession.getActiveSession.get.asInstanceOf[SnappySession].normalizeSchema
        (tableDesc.schema))
      }
      val options = Map.empty[String, String] ++ tableDesc.storage.properties

      val optionsWithPath: Map[String, String] = if (tableDesc.storage.locationUri.isDefined) {
        options + ("path" -> tableDesc.storage.locationUri.get)
      } else options
      val (provider, isBuiltIn) = SnappyContext.getBuiltInProvider(tableDesc.provider.get)
      val cmd =
        CreateMetastoreTableUsing(tableDesc.identifier, None, userSpecifiedSchema,
          None, provider, mode != SaveMode.ErrorIfExists, optionsWithPath, isBuiltIn)
      ExecutedCommandExec(cmd) :: Nil

    case CreateTable(tableDesc, mode, Some(query)) =>
      val userSpecifiedSchema = SparkSession.getActiveSession.get
          .asInstanceOf[SnappySession].normalizeSchema(query.schema)
      val options = Map.empty[String, String] ++ tableDesc.storage.properties
      val (provider, isBuiltIn) = SnappyContext.getBuiltInProvider(tableDesc.provider.get)
      val cmd = CreateMetastoreTableUsingSelect(tableDesc.identifier, None,
        Some(userSpecifiedSchema), None, provider, tableDesc.partitionColumnNames.toArray,
        mode, options, query, isBuiltIn)
      ExecutedCommandExec(cmd) :: Nil

    case CreateTableUsing(tableIdent, baseTable, userSpecifiedSchema, schemaDDL,
    provider, allowExisting, options, isBuiltIn) =>
      ExecutedCommandExec(CreateMetastoreTableUsing(tableIdent, baseTable,
        userSpecifiedSchema, schemaDDL, provider, allowExisting, options, isBuiltIn)) :: Nil

    case CreateTableUsingSelect(tableIdent, baseTable, userSpecifiedSchema, schemaDDL,
    provider, partitionColumns, mode, options, query, isBuiltIn) =>
      ExecutedCommandExec(CreateMetastoreTableUsingSelect(tableIdent, baseTable,
        userSpecifiedSchema, schemaDDL, provider, partitionColumns, mode,
        options, query, isBuiltIn)) :: Nil

    case DropTableOrView(isView: Boolean, ifExists, tableIdent) =>
      ExecutedCommandExec(DropTableOrViewCommand(isView, ifExists, tableIdent)) :: Nil

    case TruncateManagedTable(ifExists, tableIdent) =>
      ExecutedCommandExec(TruncateManagedTableCommand(ifExists, tableIdent)) :: Nil

    case AlterTableAddColumn(tableIdent, addColumn) =>
      ExecutedCommandExec(AlterTableAddColumnCommand(tableIdent, addColumn)) :: Nil

    case AlterTableToggleRowLevelSecurity(tableIdent, enableRls) =>
      ExecutedCommandExec(AlterTableToggleRowLevelSecurityCommand(tableIdent, enableRls)) :: Nil

    case AlterTableDropColumn(tableIdent, column) =>
      ExecutedCommandExec(AlterTableDropColumnCommand(tableIdent, column)) :: Nil

    case CreateIndex(indexName, baseTable, indexColumns, options) =>
      ExecutedCommandExec(CreateIndexCommand(indexName, baseTable, indexColumns, options)) :: Nil

    case CreatePolicy(policyName, tableName, policyFor, applyTo, expandedApplyTo,
    currentUser, filterStr, filter: BypassRowLevelSecurity) =>
      ExecutedCommandExec(CreatePolicyCommand(policyName, tableName, policyFor, applyTo,
        expandedApplyTo, currentUser, filterStr, filter)) :: Nil

    case DropPolicy(ifExists, policyIdent) =>
      ExecutedCommandExec(DropPolicyCommand(ifExists, policyIdent)) :: Nil

    case DropIndex(ifExists, indexName) =>
      ExecutedCommandExec(DropIndexCommand(indexName, ifExists)) :: Nil

    case SetSchema(schemaName) => ExecutedCommandExec(SetSchemaCommand(schemaName)) :: Nil

    case d@DeployCommand(_, _, _, _, _) => ExecutedCommandExec(d) :: Nil

    case d@DeployJarCommand(_, _, _) => ExecutedCommandExec(d) :: Nil

    case d@UnDeployCommand(_) => ExecutedCommandExec(d) :: Nil

    case l@ListPackageJarsCommand(_) => ExecutedCommandExec(l) :: Nil

    case SnappyStreamingActions(action, batchInterval) =>
      ExecutedCommandExec(SnappyStreamingActionsCommand(action, batchInterval)) :: Nil

    case p: EncoderPlan[_] =>
      val plan = p.asInstanceOf[EncoderPlan[Any]]
      EncoderScanExec(plan.rdd.asInstanceOf[RDD[Any]],
        plan.encoder, plan.isFlat, plan.output) :: Nil

    case InsertIntoTable(l@LogicalRelation(p: PlanInsertableRelation,
    _, _), part, query, overwrite, false) if part.isEmpty =>
      val preAction = if (overwrite.enabled) () => p.truncate() else () => ()
      ExecutePlan(p.getInsertPlan(l, planLater(query)), preAction) :: Nil

    case d@DMLExternalTable(_, storeRelation: LogicalRelation, insertCommand) =>
      ExecutedCommandExec(ExternalTableDMLCmd(storeRelation, insertCommand, d.output)) :: Nil

    case PutIntoTable(l@LogicalRelation(p: RowPutRelation, _, _), query) =>
      ExecutePlan(p.getPutPlan(l, planLater(query))) :: Nil

    case PutIntoColumnTable(LogicalRelation(p: BulkPutRelation, _, _), left, right) =>
      ExecutePlan(p.getPutPlan(planLater(left), planLater(right))) :: Nil

    case Update(l@LogicalRelation(u: MutableRelation, _, _), child,
    keyColumns, updateColumns, updateExpressions) =>
      ExecutePlan(u.getUpdatePlan(l, planLater(child), updateColumns,
        updateExpressions, keyColumns)) :: Nil

    case Delete(l@LogicalRelation(d: MutableRelation, _, _), child, keyColumns) =>
      ExecutePlan(d.getDeletePlan(l, planLater(child), keyColumns)) :: Nil

    case DeleteFromTable(l@LogicalRelation(d: DeletableRelation, _, _), query) =>
      ExecutePlan(d.getDeletePlan(l, planLater(query), query.output)) :: Nil

    case r: RunnableCommand => ExecutedCommandExec(r) :: Nil

    case _ => Nil
  }
}

// marker trait to indicate a plan that can mutate a table
trait TableMutationPlan

case class ExternalTableDMLCmd(
    storeRelation: LogicalRelation,
    command: String, childOutput: Seq[Attribute]) extends RunnableCommand with TableMutationPlan {

  override def run(session: SparkSession): Seq[Row] = {
    storeRelation.relation match {
      case relation: SingleRowInsertableRelation =>
        Seq(Row(relation.executeUpdate(command, session.catalog.currentDatabase)))
      case other => throw new AnalysisException("DML support requires " +
          "SingleRowInsertableRelation but found " + other)
    }
  }

  override lazy val output: Seq[Attribute] = childOutput
}

case class PutIntoTable(table: LogicalPlan, child: LogicalPlan)
    extends LogicalPlan with TableMutationPlan {

  override def children: Seq[LogicalPlan] = table :: child :: Nil

  override lazy val output: Seq[Attribute] = AttributeReference(
    "count", LongType)() :: Nil

  override lazy val resolved: Boolean = childrenResolved &&
      child.output.zip(table.output).forall {
        case (childAttr, tableAttr) =>
          DataType.equalsIgnoreCompatibleNullability(childAttr.dataType,
            tableAttr.dataType)
      }
}

/**
 * Unlike Spark's InsertIntoTable this plan provides the count of rows
 * inserted as the output.
 */
final class Insert(
    table: LogicalPlan,
    partition: Map[String, Option[String]],
    child: LogicalPlan,
    overwrite: OverwriteOptions,
    ifNotExists: Boolean)
    extends InsertIntoTable(table, partition, child, overwrite, ifNotExists) {

  override def output: Seq[Attribute] = AttributeReference(
    "count", LongType)() :: Nil

  override def copy(table: LogicalPlan = table,
      partition: Map[String, Option[String]] = partition,
      child: LogicalPlan = child,
      overwrite: OverwriteOptions = overwrite,
      ifNotExists: Boolean = ifNotExists): Insert = {
    new Insert(table, partition, child, overwrite, ifNotExists)
  }
}

case class Update(table: LogicalPlan, child: LogicalPlan,
    keyColumns: Seq[Attribute], updateColumns: Seq[Attribute],
    updateExpressions: Seq[Expression]) extends LogicalPlan with TableMutationPlan {

  assert(updateColumns.length == updateExpressions.length,
    s"Internal error: updateColumns=${updateColumns.length} " +
        s"updateExpressions=${updateExpressions.length}")

  override def children: Seq[LogicalPlan] = table :: child :: Nil

  override lazy val output: Seq[Attribute] = AttributeReference(
    "count", LongType)() :: Nil
}

case class Delete(table: LogicalPlan, child: LogicalPlan,
    keyColumns: Seq[Attribute]) extends LogicalPlan with TableMutationPlan {

  override def children: Seq[LogicalPlan] = table :: child :: Nil

  override lazy val output: Seq[Attribute] = AttributeReference(
    "count", LongType)() :: Nil

  override lazy val resolved: Boolean = childrenResolved
}

private[sql] case class DeleteFromTable(
    table: LogicalPlan,
    child: LogicalPlan)
    extends LogicalPlan with TableMutationPlan {

  override def children: Seq[LogicalPlan] = table :: child :: Nil

  override def output: Seq[Attribute] = Nil

  override lazy val resolved: Boolean = childrenResolved &&
      child.output.zip(table.output).forall {
        case (childAttr, tableAttr) =>
          DataType.equalsIgnoreCompatibleNullability(childAttr.dataType,
            tableAttr.dataType)
      }
}
