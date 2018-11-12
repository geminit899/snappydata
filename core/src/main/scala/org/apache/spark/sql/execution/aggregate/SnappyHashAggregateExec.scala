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
/*
 * Some code adapted from Spark's HashAggregateExec having the license below.
 */
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.aggregate

import io.snappydata.collection.ObjectHashSet

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.types.{ArrayType, BinaryType, MapType, StringType, StructType}
import org.apache.spark.sql.{SnappySession, collection}
import org.apache.spark.util.Utils

/**
 * Hash-based aggregate operator that can also fallback to sorting when
 * data exceeds memory size.
 *
 * Parts of this class have been adapted from Spark's HashAggregateExec.
 * That class is not extended because it forces that the limitations
 * <code>HashAggregateExec.supportsAggregate</code> of that implementation in
 * the constructor itself while this implementation has no such restriction.
 */
case class SnappyHashAggregateExec(
    requiredChildDistributionExpressions: Option[Seq[Expression]],
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    aggregateAttributes: Seq[Attribute],
    __resultExpressions: Seq[NamedExpression],
    child: SparkPlan,
    hasDistinct: Boolean)
    extends NonRecursivePlans with UnaryExecNode with BatchConsumer {

  override def nodeName: String = "SnappyHashAggregate"

  @transient def resultExpressions: Seq[NamedExpression] = __resultExpressions

  @transient lazy private[this] val aggregateBufferAttributes = {
    aggregateExpressions.flatMap(_.aggregateFunction.aggBufferAttributes)
  }

  @transient lazy private[this] val aggregateBufferAttributesForGroup = {
    aggregateExpressions.flatMap(a => bufferAttributesForGroup(
      a.aggregateFunction))
  }

  override lazy val allAttributes: AttributeSeq =
    child.output ++ aggregateBufferAttributes ++ aggregateAttributes ++
        aggregateExpressions.flatMap(_.aggregateFunction
            .inputAggBufferAttributes)

  @transient val (metricAdd, _): (String => String, String => String) =
    collection.Utils.metricMethods

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext,
      "number of output rows"),
    "peakMemory" -> SQLMetrics.createSizeMetric(sparkContext, "peak memory"),
    "spillSize" -> SQLMetrics.createSizeMetric(sparkContext, "spill size"),
    "aggTime" -> SQLMetrics.createTimingMetric(sparkContext, "aggregate time"))

  // this is a var to allow CollectAggregateExec to switch temporarily
  @transient private[execution] var childProducer = child

  private def getChildProducer: SparkPlan = {
    if (childProducer eq null) {
      childProducer = child
    }
    childProducer
  }

  override def output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

  override def producedAttributes: AttributeSet =
    AttributeSet(aggregateAttributes) ++
        AttributeSet(resultExpressions.diff(groupingExpressions)
            .map(_.toAttribute)) ++
        AttributeSet(aggregateBufferAttributes)

  override def outputPartitioning: Partitioning = {
    child.outputPartitioning
  }

  override def requiredChildDistribution: List[Distribution] = {
    requiredChildDistributionExpressions match {
      case Some(exprs) if exprs.isEmpty => AllTuples :: Nil
      case Some(exprs) if exprs.nonEmpty => ClusteredDistribution(exprs) :: Nil
      case None => UnspecifiedDistribution :: Nil
    }
  }

  /**
   * Optimized attributes for grouping that are non-nullable when the
   * children are. The case of no results for a group are handled by the fact
   * that if that happens then no row for the group will be present at all.
   */
  private def bufferAttributesForGroup(
      aggregate: AggregateFunction): Seq[AttributeReference] = aggregate match {
    case g: GroupAggregate => g.aggBufferAttributesForGroup
    case sum: Sum if !sum.child.nullable =>
      val sumAttr = sum.aggBufferAttributes.head
      sumAttr.copy(nullable = false)(sumAttr.exprId, sumAttr.qualifier,
        sumAttr.isGenerated) :: Nil
    case avg: Average if !avg.child.nullable =>
      val sumAttr = avg.aggBufferAttributes.head
      sumAttr.copy(nullable = false)(sumAttr.exprId, sumAttr.qualifier,
        sumAttr.isGenerated) :: avg.aggBufferAttributes(1) :: Nil
    case max: Max if !max.child.nullable =>
      val maxAttr = max.aggBufferAttributes.head
      maxAttr.copy(nullable = false)(maxAttr.exprId, maxAttr.qualifier,
        maxAttr.isGenerated) :: Nil
    case min: Min if !min.child.nullable =>
      val minAttr = min.aggBufferAttributes.head
      minAttr.copy(nullable = false)(minAttr.exprId, minAttr.qualifier,
        minAttr.isGenerated) :: Nil
    case last: Last if !last.child.nullable =>
      val lastAttr = last.aggBufferAttributes.head
      val tail = if (last.aggBufferAttributes.length == 2) {
        val valueSetAttr = last.aggBufferAttributes(1)
        valueSetAttr.copy(nullable = false)(valueSetAttr.exprId,
          valueSetAttr.qualifier, valueSetAttr.isGenerated) :: Nil
      } else Nil
      lastAttr.copy(nullable = false)(lastAttr.exprId, lastAttr.qualifier,
        lastAttr.isGenerated) :: tail
    case _ => aggregate.aggBufferAttributes
  }

  private def bufferInitialValuesForGroup(
      aggregate: AggregateFunction): Seq[Expression] = aggregate match {
    case g: GroupAggregate => g.initialValuesForGroup
    case sum: Sum if !sum.child.nullable => Seq(Cast(Literal(0), sum.dataType))
    case _ => aggregate.asInstanceOf[DeclarativeAggregate].initialValues
  }

  // This is for testing. We force TungstenAggregationIterator to fall back
  // to the unsafe row hash map and/or the sort-based aggregation once it has
  // processed a given number of input rows.
  private val testFallbackStartsAt: Option[(Int, Int)] = {
    sqlContext.getConf("spark.sql.TungstenAggregate.testFallbackStartsAt",
      null) match {
      case null | "" => None
      case fallbackStartsAt =>
        val splits = fallbackStartsAt.split(",").map(_.trim)
        Some((splits.head.toInt, splits.last.toInt))
    }
  }

  // all the mode of aggregate expressions
  private val modes = aggregateExpressions.map(_.mode).distinct

  // return empty for grouping case as code of required variables
  // is explicitly instantiated for that case
  override def usedInputs: AttributeSet = {
    if (groupingExpressions.isEmpty) inputSet else AttributeSet.empty
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    child.asInstanceOf[CodegenSupport].inputRDDs()
  }



  override protected def doProduce(ctx: CodegenContext): String = {
    if (groupingExpressions.isEmpty) {
      doProduceWithoutKeys(ctx)
    } else {
      doProduceWithKeys(ctx)
    }
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode],
      row: ExprCode): String = {
    if (groupingExpressions.isEmpty) {
      doConsumeWithoutKeys(ctx, input)
    } else {
      doConsumeWithKeys(ctx, input)
    }
  }

  override def canConsume(plan: SparkPlan): Boolean = {
    if (groupingExpressions.isEmpty) return true // for beforeStop()
    // check for possible optimized dictionary code path;
    // below is a loose search while actual decision will be taken as per
    // availability of ExprCodeEx with DictionaryCode in doConsume
    DictionaryOptimizedMapAccessor.canHaveSingleKeyCase(
      keyBufferAccessor.keyExpressions)
  }

  override def batchConsume(ctx: CodegenContext, plan: SparkPlan,
      input: Seq[ExprCode]): String = {
    if (groupingExpressions.isEmpty || !canConsume(plan)) ""
    else {
      // create an empty method to populate the dictionary array
      // which will be actually filled with code in consume if the dictionary
      // optimization is possible using the incoming DictionaryCode
      val className = keyBufferAccessor.getClassName
      // this array will be used at batch level for grouping if possible
      dictionaryArrayTerm = ctx.freshName("dictionaryArray")
      dictionaryArrayInit = ctx.freshName("dictionaryArrayInit")
      ctx.addNewFunction(dictionaryArrayInit,
        s"""
           |private $className[] $dictionaryArrayInit() {
           |  return null;
           |}
         """.stripMargin)
      s"final $className[] $dictionaryArrayTerm = $dictionaryArrayInit();"
    }
  }

  override def beforeStop(ctx: CodegenContext, plan: SparkPlan,
      input: Seq[ExprCode]): String = {
    if (bufVars eq null) ""
    else {
      bufVarUpdates = bufVars.indices.map { i =>
        val ev = bufVars(i)
        s"""
           |// update the member result variables from local variables
           |this.${ev.isNull} = ${ev.isNull};
           |this.${ev.value} = ${ev.value};
        """.stripMargin
      }.mkString("\n").trim
      bufVarUpdates
    }
  }

  // The variables used as aggregation buffer
  @transient protected var bufVars: Seq[ExprCode] = _
  // code to update buffer variables with current values
  @transient protected var bufVarUpdates: String = _

  private def doProduceWithoutKeys(ctx: CodegenContext): String = {
    val initAgg = ctx.freshName("initAgg")
    ctx.addMutableState("boolean", initAgg, s"$initAgg = false;")

    // generate variables for aggregation buffer
    val functions = aggregateExpressions.map(_.aggregateFunction
        .asInstanceOf[DeclarativeAggregate])
    val initExpr = functions.flatMap(f => f.initialValues)
    bufVars = initExpr.map { e =>
      val isNull = ctx.freshName("bufIsNull")
      val value = ctx.freshName("bufValue")
      ctx.addMutableState("boolean", isNull, "")
      ctx.addMutableState(ctx.javaType(e.dataType), value, "")
      // The initial expression should not access any column
      val ev = e.genCode(ctx)
      val initVars =
        s"""
           | $isNull = ${ev.isNull};
           | $value = ${ev.value};
        """.stripMargin
      ExprCode(ev.code + initVars, isNull, value)
    }
    var initBufVar = evaluateVariables(bufVars)

    // generate variables for output
    val (resultVars, genResult) = if (modes.contains(Final) ||
        modes.contains(Complete)) {
      // evaluate aggregate results
      ctx.currentVars = bufVars
      val aggResults = functions.map(_.evaluateExpression).map { e =>
        BindReferences.bindReference(e, aggregateBufferAttributes).genCode(ctx)
      }
      val evaluateAggResults = evaluateVariables(aggResults)
      // evaluate result expressions
      ctx.currentVars = aggResults
      val resultVars = resultExpressions.map { e =>
        BindReferences.bindReference(e, aggregateAttributes).genCode(ctx)
      }
      (resultVars,
          s"""
             |$evaluateAggResults
             |${evaluateVariables(resultVars)}
          """.stripMargin)
    } else if (modes.contains(Partial) || modes.contains(PartialMerge)) {
      // output the aggregate buffer directly
      (bufVars, "")
    } else {
      // no aggregate function, the result should be literals
      val resultVars = resultExpressions.map(_.genCode(ctx))
      (resultVars, evaluateVariables(resultVars))
    }

    val doAgg = ctx.freshName("doAggregateWithoutKey")
    var produceOutput = getChildProducer.asInstanceOf[CodegenSupport].produce(
      ctx, this)
    if (bufVarUpdates ne null) {
      // use local variables while member variables are updated at the end
      initBufVar = bufVars.indices.map { i =>
        val ev = bufVars(i)
        s"""
           |boolean ${ev.isNull} = this.${ev.isNull};
           |${ctx.javaType(initExpr(i).dataType)} ${ev.value} = this.${ev.value};
        """.stripMargin
      }.mkString("", "\n", initBufVar).trim
      produceOutput = s"$produceOutput\n$bufVarUpdates"
    }
    ctx.addNewFunction(doAgg,
      s"""
         |private void $doAgg() throws java.io.IOException {
         |  // initialize aggregation buffer
         |  $initBufVar
         |
         |  $produceOutput
         |}
       """.stripMargin)

    val numOutput = metricTerm(ctx, "numOutputRows")
    val aggTime = metricTerm(ctx, "aggTime")
    val beforeAgg = ctx.freshName("beforeAgg")
    s"""
       |while (!$initAgg) {
       |  $initAgg = true;
       |  long $beforeAgg = System.nanoTime();
       |  $doAgg();
       |  $aggTime.${metricAdd(s"(System.nanoTime() - $beforeAgg) / 1000000")};
       |
       |  // output the result
       |  ${genResult.trim}
       |
       |  $numOutput.${metricAdd("1")};
       |  ${consume(ctx, resultVars).trim}
       |}
     """.stripMargin
  }

  protected def genAssignCodeForWithoutKeys(ev: ExprCode, i: Int, doCopy: Boolean,
      inputAttrs: Seq[Attribute]): String = {
    if (doCopy) {
      inputAttrs(i).dataType match {
        case StringType =>
          ObjectHashMapAccessor.cloneStringIfRequired(ev.value, bufVars(i).value, doCopy = true)
        case _: ArrayType | _: MapType | _: StructType =>
          s"${bufVars(i).value} = ${ev.value} != null ? ${ev.value}.copy() : null;"
        case _: BinaryType =>
          s"${bufVars(i).value} = ${ev.value} != null ? ${ev.value}.clone() : null;"
        case _ => s"${bufVars(i).value} = ${ev.value};"
      }
    } else s"${bufVars(i).value} = ${ev.value};"
  }

  private def doConsumeWithoutKeys(ctx: CodegenContext,
      input: Seq[ExprCode]): String = {
    // only have DeclarativeAggregate
    val functions = aggregateExpressions.map(_.aggregateFunction
        .asInstanceOf[DeclarativeAggregate])
    val inputAttrs = functions.flatMap(_.aggBufferAttributes) ++ child.output
    val updateExpr = aggregateExpressions.flatMap { e =>
      val aggregate = e.aggregateFunction.asInstanceOf[DeclarativeAggregate]
      e.mode match {
        case Partial | Complete => aggregate.updateExpressions
        case PartialMerge | Final => aggregate.mergeExpressions
      }
    }
    ctx.currentVars = bufVars ++ input
    val boundUpdateExpr = updateExpr.map(BindReferences.bindReference(_,
      inputAttrs))
    val subExprs = ctx.subexpressionEliminationForWholeStageCodegen(
      boundUpdateExpr)
    val effectiveCodes = subExprs.codes.mkString("\n")
    val aggVals = ctx.withSubExprEliminationExprs(subExprs.states) {
      boundUpdateExpr.map(_.genCode(ctx))
    }
    // aggregate buffer should be updated atomic
    // make copy of results for types that can be wrappers and thus mutable
    val doCopy = !ObjectHashMapAccessor.providesImmutableObjects(child)
    val updates = aggVals.zipWithIndex.map { case (ev, i) =>
      s"""
         | ${bufVars(i).isNull} = ${ev.isNull};
         | ${genAssignCodeForWithoutKeys(ev, i, doCopy, inputAttrs)}
      """.stripMargin
    }
    s"""
       | // do aggregate
       | // common sub-expressions
       | $effectiveCodes
       | // evaluate aggregate functions
       | ${evaluateVariables(aggVals)}
       | // update aggregation buffer
       | ${updates.mkString("\n").trim}
    """.stripMargin
  }

  private val groupingAttributes = groupingExpressions.map(_.toAttribute)
  private val declFunctions = aggregateExpressions.map(_.aggregateFunction)
      .collect { case d: DeclarativeAggregate => d }

  // The name for ObjectHashSet of generated class.
  private var hashMapTerm: String = _

  // utility to generate class for optimized map, and hash map access methods
  @transient private var keyBufferAccessor: ObjectHashMapAccessor = _
  @transient private var mapDataTerm: String = _
  @transient private var maskTerm: String = _
  @transient private var dictionaryArrayTerm: String = _
  @transient private var dictionaryArrayInit: String = _

  /**
   * Generate the code for output.
   */
  private def generateResultCode(
      ctx: CodegenContext, keyBufferVars: Seq[ExprCode],
      bufferIndex: Int): String = {
    if (modes.contains(Final) || modes.contains(Complete)) {
      // generate output extracting from ExprCodes
      ctx.INPUT_ROW = null
      ctx.currentVars = keyBufferVars.take(bufferIndex)
      val keyVars = groupingExpressions.zipWithIndex.map {
        case (e, i) => BoundReference(i, e.dataType, e.nullable).genCode(ctx)
      }
      val evaluateKeyVars = evaluateVariables(keyVars)
      ctx.currentVars = keyBufferVars.drop(bufferIndex)
      val bufferVars = aggregateBufferAttributesForGroup.zipWithIndex.map {
        case (e, i) => BoundReference(i, e.dataType, e.nullable).genCode(ctx)
      }
      val evaluateBufferVars = evaluateVariables(bufferVars)
      // evaluate the aggregation result
      ctx.currentVars = bufferVars
      val aggResults = declFunctions.map(_.evaluateExpression).map { e =>
        BindReferences.bindReference(e, aggregateBufferAttributesForGroup)
            .genCode(ctx)
      }
      val evaluateAggResults = evaluateVariables(aggResults)
      // generate the final result
      ctx.currentVars = keyVars ++ aggResults
      val inputAttrs = groupingAttributes ++ aggregateAttributes
      val resultVars = resultExpressions.map { e =>
        BindReferences.bindReference(e, inputAttrs).genCode(ctx)
      }
      s"""
       $evaluateKeyVars
       $evaluateBufferVars
       $evaluateAggResults
       ${consume(ctx, resultVars)}
       """

    } else if (modes.contains(Partial) || modes.contains(PartialMerge)) {
      // Combined grouping keys and aggregate values in buffer
      consume(ctx, keyBufferVars)

    } else {
      // generate result based on grouping key
      ctx.INPUT_ROW = null
      ctx.currentVars = keyBufferVars.take(bufferIndex)
      val eval = resultExpressions.map { e =>
        BindReferences.bindReference(e, groupingAttributes).genCode(ctx)
      }
      consume(ctx, eval)
    }
  }

  private def doProduceWithKeys(ctx: CodegenContext): String = {
    val initAgg = ctx.freshName("initAgg")
    ctx.addMutableState("boolean", initAgg, s"$initAgg = false;")

    // Create a name for iterator from HashMap
    val iterTerm = ctx.freshName("mapIter")
    val iter = ctx.freshName("mapIter")
    val iterObj = ctx.freshName("iterObj")
    val iterClass = "java.util.Iterator"
    ctx.addMutableState(iterClass, iterTerm, "")

    val doAgg = ctx.freshName("doAggregateWithKeys")

    // generate variable name for hash map for use here and in consume
    hashMapTerm = ctx.freshName("hashMap")
    val hashSetClassName = classOf[ObjectHashSet[_]].getName
    ctx.addMutableState(hashSetClassName, hashMapTerm, "")

    // generate variables for HashMap data array and mask
    mapDataTerm = ctx.freshName("mapData")
    maskTerm = ctx.freshName("hashMapMask")

    // generate the map accessor to generate key/value class
    // and get map access methods
    val session = sqlContext.sparkSession.asInstanceOf[SnappySession]
    keyBufferAccessor = ObjectHashMapAccessor(session, ctx, groupingExpressions,
      aggregateBufferAttributesForGroup, "KeyBuffer", hashMapTerm,
      mapDataTerm, maskTerm, multiMap = false, this, this.parent, child)

    val entryClass = keyBufferAccessor.getClassName
    val numKeyColumns = groupingExpressions.length

    val childProduce =
      childProducer.asInstanceOf[CodegenSupport].produce(ctx, this)
    ctx.addNewFunction(doAgg,
      s"""
        private void $doAgg() throws java.io.IOException {
          $hashMapTerm = new $hashSetClassName(128, 0.6, $numKeyColumns, false,
            scala.reflect.ClassTag$$.MODULE$$.apply($entryClass.class));
          $entryClass[] $mapDataTerm = ($entryClass[])$hashMapTerm.data();
          int $maskTerm = $hashMapTerm.mask();

          $childProduce

          $iterTerm = $hashMapTerm.iterator();
        }
       """)

    // generate code for output
    val keyBufferTerm = ctx.freshName("keyBuffer")
    val (initCode, keyBufferVars, _) = keyBufferAccessor.getColumnVars(
      keyBufferTerm, keyBufferTerm, onlyKeyVars = false, onlyValueVars = false)
    val outputCode = generateResultCode(ctx, keyBufferVars,
      groupingExpressions.length)
    val numOutput = metricTerm(ctx, "numOutputRows")

    // The child could change `copyResult` to true, but we had already
    // consumed all the rows, so `copyResult` should be reset to `false`.
    ctx.copyResult = false

    val aggTime = metricTerm(ctx, "aggTime")
    val beforeAgg = ctx.freshName("beforeAgg")

    s"""
      if (!$initAgg) {
        $initAgg = true;
        long $beforeAgg = System.nanoTime();
        $doAgg();
        $aggTime.${metricAdd(s"(System.nanoTime() - $beforeAgg) / 1000000")};
      }

      // output the result
      Object $iterObj;
      final $iterClass $iter = $iterTerm;
      while (($iterObj = $iter.next()) != null) {
        $numOutput.${metricAdd("1")};
        final $entryClass $keyBufferTerm = ($entryClass)$iterObj;
        $initCode

        $outputCode

        if (shouldStop()) return;
      }
    """
  }

  private def doConsumeWithKeys(ctx: CodegenContext,
      input: Seq[ExprCode]): String = {
    val keyBufferTerm = ctx.freshName("keyBuffer")

    // only have DeclarativeAggregate
    val updateExpr = aggregateExpressions.flatMap { e =>
      e.mode match {
        case Partial | Complete =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate]
              .updateExpressions
        case PartialMerge | Final =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate]
              .mergeExpressions
      }
    }

    // generate class for key, buffer and hash code evaluation of key columns
    val inputAttr = aggregateBufferAttributesForGroup ++ child.output
    val initVars = ctx.generateExpressions(declFunctions.flatMap(
      bufferInitialValuesForGroup(_).map(BindReferences.bindReference(_,
        inputAttr))))
    val initCode = evaluateVariables(initVars)
    val (bufferInit, bufferVars, _) = keyBufferAccessor.getColumnVars(
      keyBufferTerm, keyBufferTerm, onlyKeyVars = false, onlyValueVars = true)
    val bufferEval = evaluateVariables(bufferVars)

    // if aggregate expressions uses some of the key variables then signal those
    // to be materialized explicitly for the dictionary optimization case (AQP-292)
    val updateAttrs = AttributeSet(updateExpr)
    val evalKeys = if (DictionaryOptimizedMapAccessor.canHaveSingleKeyCase(groupingExpressions)) {
      var hasEvalKeys = false
      val keys = groupingAttributes.map { a =>
        if (updateAttrs.contains(a)) {
          hasEvalKeys = true
          true
        } else false
      }
      if (hasEvalKeys) keys else Nil
    } else Nil

    // evaluate map lookup code before updateEvals possibly modifies the keyVars
    val mapCode = keyBufferAccessor.generateMapGetOrInsert(keyBufferTerm,
      initVars, initCode, input, evalKeys, dictionaryArrayTerm, dictionaryArrayInit)

    ctx.currentVars = bufferVars ++ input
    // pre-evaluate input variables used by child expressions and updateExpr
    val inputCodes = evaluateRequiredVariables(child.output,
      ctx.currentVars.takeRight(child.output.length),
      child.references ++ updateAttrs)
    val boundUpdateExpr = updateExpr.map(BindReferences.bindReference(_,
      inputAttr))
    val subExprs = ctx.subexpressionEliminationForWholeStageCodegen(
      boundUpdateExpr)
    val effectiveCodes = subExprs.codes.mkString("\n")
    val updateEvals = ctx.withSubExprEliminationExprs(subExprs.states) {
      boundUpdateExpr.map(_.genCode(ctx))
    }

    // We first generate code to probe and update the hash map. If the probe is
    // successful, the corresponding buffer will hold buffer class object.
    // We try to do hash map based in-memory aggregation first. If there is not
    // enough memory (the hash map will return null for new key), we spill the
    // hash map to disk to free memory, then continue to do in-memory
    // aggregation and spilling until all the rows had been processed.
    // Finally, sort the spilled aggregate buffers by key, and merge
    // them together for same key.
    s"""
       |$mapCode
       |
       |// -- Update the buffer with new aggregate results --
       |
       |// initialization for buffer fields
       |$bufferInit
       |$bufferEval
       |
       |// common sub-expressions
       |$inputCodes
       |$effectiveCodes
       |
       |// evaluate aggregate functions
       |${evaluateVariables(updateEvals)}
       |
       |// update generated class object fields
       |${keyBufferAccessor.generateUpdate(keyBufferTerm, bufferVars,
          updateEvals, forKey = false, doCopy = false, forInit = false)}
    """.stripMargin
  }

  override def verboseString: String = toString(verbose = true)

  override lazy val simpleString: String = toString(verbose = false)

  private def toString(verbose: Boolean): String = {
    val name = nodeName
    val allAggregateExpressions = aggregateExpressions

    testFallbackStartsAt match {
      case None =>
        val keyString = Utils.truncatedString(groupingExpressions,
          "[", ", ", "]")
        val functionString = Utils.truncatedString(allAggregateExpressions,
          "[", ", ", "]")
        val outputString = Utils.truncatedString(output, "[", ", ", "]")
        val modesStr = modes.mkString(",")
        if (verbose) {
          s"$name(keys=$keyString, modes=$modesStr, " +
              s"functions=$functionString, output=$outputString)"
        } else {
          s"$name(keys=$keyString, modes=$modesStr, functions=$functionString)"
        }
      case Some(fallbackStartsAt) =>
        s"${name}WithControlledFallback $groupingExpressions " +
            s"$allAggregateExpressions $resultExpressions " +
            s"fallbackStartsAt=$fallbackStartsAt"
    }
  }
}

trait GroupAggregate {

  /**
   * Expressions for initializing empty aggregation buffers for group by.
   */
  def initialValuesForGroup: Seq[Expression]

  /** Attributes of fields in aggBufferSchema used for group by. */
  def aggBufferAttributesForGroup: Seq[AttributeReference]
}
