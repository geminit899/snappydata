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

package io.snappydata.hydra.streaming_sink

import java.io.{File, FileOutputStream, PrintWriter}

import com.typesafe.config.Config

import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.streaming.{ProcessingTime, SchemaDStream, SnappyStreamingJob}
import org.apache.spark.sql.types.{IntegerType, LongType, StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SnappyJobValid, SnappyJobValidation}
import org.apache.spark.streaming.SnappyStreamingContext


class SnappyStreamingSinkJob extends SnappyStreamingJob {

  override def runSnappyJob(snsc: SnappyStreamingContext, jobConfig: Config): Any = {
    val tid: Int = jobConfig.getString("tid").toInt
    var brokerList: String = jobConfig.getString("brokerList")
    brokerList = brokerList.replace("--", ":")
    val kafkaTopic: String = jobConfig.getString("kafkaTopic")
    val tableName: String = "persoon"
    val checkpointDirectory: String = (new File(".")).getCanonicalPath +
        File.separator + "checkpointDirectory_" + tid
    // Spark tip : Keep shuffle count low when data volume is low.
    snsc.sql("set spark.sql.shuffle.partitions=8")
    val outputFile = "KafkaStreamingJob_output" + tid + "_" + System.currentTimeMillis() + ".txt"
    val pw = new PrintWriter(new FileOutputStream(new File(outputFile), true));
    // scalastyle:off println
    pw.println("Starting stream query...")
    pw.flush()
    import snsc.snappySession.implicits._

    createAndStartStreamingQuery(kafkaTopic, tid, withEventTypeColumn = false)
    pw.println("started streaming query")
    pw.flush()
    def createAndStartStreamingQuery(topic: String, testId: Int,
        withEventTypeColumn: Boolean = true, failBatch: Boolean = false) = {
      val session = snsc.snappySession
      val streamingDF = session
          .readStream
          .format("kafka")
          .option("kafka.bootstrap.servers", brokerList)
//          .option("kafka.value.deserializer", "")
          .option("subscribe", topic)
          .option("startingOffsets", "earliest")
          .load()

      def structFields() = {
        StructField("id", LongType, nullable = false) ::
            StructField("firstName", StringType, nullable = true) ::
            StructField("middleName", StringType, nullable = true) ::
            StructField("lastName", StringType, nullable = true) ::
            StructField("title", StringType, nullable = true) ::
            StructField("address", StringType, nullable = true) ::
            StructField("country", StringType, nullable = true) ::
            StructField("phone", StringType, nullable = true) ::
            StructField("dateOfBirth", StringType, nullable = true) ::
            StructField("age", IntegerType, nullable = true) ::
            StructField("status", StringType, nullable = true) ::
            StructField("email", StringType, nullable = true) ::
            StructField("education", StringType, nullable = true) ::
            StructField("occupation", StringType, nullable = true) ::
            (if (withEventTypeColumn) {
              StructField("_eventType", IntegerType, nullable = false) :: Nil
            }
            else {
              Nil
            })
      }

      val schema = StructType(structFields())
      implicit val encoder = RowEncoder(schema)
      structFields().length
      streamingDF.selectExpr("CAST(value AS STRING)")
          .as[String]
          .map(_.split(","))
          .map(r => {
            if (r.length == 15) {
              Row(r(0).toLong, r(1), r(2), r(3), r(4), r(5), r(6), r(7), r(8), r(9).toInt, r(10),
                r(11), r(12), r(13), r(14).toInt)
            } else {
              Row(r(0).toLong, r(1), r(2), r(3), r(4), r(5), r(6), r(7), r(8), r(9).toInt, r(10),
                r(11), r(12), r(13))
            }
          })
          .writeStream
          .format("snappysink")
          .queryName(s"USERS_$testId")
          .trigger(ProcessingTime("1 seconds"))
          .option("tableName", tableName)
          .option("streamQueryId", "Query" + testId)
          .option("checkpointLocation", checkpointDirectory).start
    }
  }

  override def isValidJob(snsc: SnappyStreamingContext, config: Config): SnappyJobValidation = {
    SnappyJobValid()
  }
}

