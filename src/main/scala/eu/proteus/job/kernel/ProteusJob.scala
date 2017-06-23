/*
 * Copyright (C) 2017 The Proteus Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.proteus.job.kernel

import java.util.Properties

import eu.proteus.job.operations.data.model.{CoilMeasurement, SensorMeasurement1D, SensorMeasurement2D}
import eu.proteus.job.operations.data.results.{MomentsResult, MomentsResult1D, MomentsResult2D}
import eu.proteus.job.operations.data.serializer.schema.UntaggedObjectSerializationSchema
import eu.proteus.job.operations.data.serializer.{CoilMeasurementKryoSerializer, MomentsResultKryoSerializer}
import eu.proteus.job.operations.moments.MomentsOperation
import eu.proteus.solma
import grizzled.slf4j.Logger
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend
import org.apache.flink.runtime.state.memory.MemoryStateBackend
import org.apache.flink.streaming.api.{CheckpointingMode, TimeCharacteristic}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer010, FlinkKafkaProducer010}

import scala.collection.mutable


object ProteusJob {

  private [kernel] val LOG = Logger(getClass)
  private [kernel] val ONE_MEGABYTE = 1024 * 1024
  private [kernel] val ONE_MINUTE_IN_MS = 60 * 1000


  // kafka config
  private [kernel] var kafkaBootstrapServer = "localhost:2181"
  private [kernel] var realtimeDataKafkaTopic = "proteus-realtime"
  private [kernel] var jobStackBackendType = "memory"

  // flink config
  private [kernel] var flinkCheckpointsDir = ""
  private [kernel] var memoryBackendMBSize = 20
  private [kernel] var flinkCheckpointsInterval = 10


  def loadBaseKafkaProperties = {
    val properties = new Properties
    properties.setProperty("bootstrap.servers", kafkaBootstrapServer)
//    properties.setProperty("group.id", "proteus-group")
    properties
  }

  def loadConsumerKafkaProperties = {
    val properties = loadBaseKafkaProperties
//    properties.setProperty("auto.offset.reset", "earliest")
    properties
  }


  def configureFlinkEnv(env: StreamExecutionEnvironment) = {
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    if (jobStackBackendType == "rocksdb") {
      val stateBackend = new RocksDBStateBackend(flinkCheckpointsDir, false)
//      stateBackend.setPredefinedOptions(PredefinedOptions.DEFAULT)
      env.setStateBackend(stateBackend)
    } else if (jobStackBackendType == "memory") {
      val stateBackend = new MemoryStateBackend(memoryBackendMBSize * ONE_MEGABYTE, true)
      env.setStateBackend(stateBackend)
    } else {
      throw new UnsupportedOperationException
    }

    env.enableCheckpointing(flinkCheckpointsInterval * ONE_MINUTE_IN_MS, CheckpointingMode.EXACTLY_ONCE)

    val cfg = env.getConfig

    // register types
    cfg.registerKryoType(classOf[CoilMeasurement])
    cfg.registerKryoType(classOf[SensorMeasurement2D])
    cfg.registerKryoType(classOf[SensorMeasurement1D])
    cfg.registerKryoType(classOf[MomentsResult])
    cfg.registerKryoType(classOf[MomentsResult1D])
    cfg.registerKryoType(classOf[MomentsResult2D])
    cfg.registerKryoType(classOf[solma.moments.MomentsEstimator.Moments])

    // register serializers
    env.addDefaultKryoSerializer(classOf[CoilMeasurement], classOf[CoilMeasurementKryoSerializer])
    env.addDefaultKryoSerializer(classOf[SensorMeasurement2D], classOf[CoilMeasurementKryoSerializer])
    env.addDefaultKryoSerializer(classOf[SensorMeasurement1D], classOf[CoilMeasurementKryoSerializer])
    env.addDefaultKryoSerializer(classOf[MomentsResult], classOf[MomentsResultKryoSerializer])
    env.addDefaultKryoSerializer(classOf[MomentsResult1D], classOf[MomentsResultKryoSerializer])
    env.addDefaultKryoSerializer(classOf[MomentsResult2D], classOf[MomentsResultKryoSerializer])

    env.addDefaultKryoSerializer(classOf[mutable.Queue[_]],
      classOf[com.twitter.chill.TraversableSerializer[_, mutable.Queue[_]]])
  }


  def startProteusJob(parameters: ParameterTool) = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // create the job
    configureFlinkEnv(env)

    // type info & serializer
    implicit val inputTypeInfo = createTypeInformation[CoilMeasurement]
    val inputSchema = new UntaggedObjectSerializationSchema[CoilMeasurement](env.getConfig)

    // add kafka source

    val source: DataStream[CoilMeasurement] = env.addSource(new FlinkKafkaConsumer010[CoilMeasurement](
        realtimeDataKafkaTopic,
        inputSchema,
        loadConsumerKafkaProperties))

    // simple moments

    val moments = MomentsOperation.runSimpleMomentsAnalytics(source, 54)
    implicit val momentsTypeInfo = createTypeInformation[MomentsResult]
    val momentsSinkSchema = new UntaggedObjectSerializationSchema[MomentsResult](env.getConfig)

    //val momentsSinkSchema = new SimpleStringSchema()

    val producerCfg = FlinkKafkaProducer010.writeToKafkaWithTimestamps(
        moments.javaStream,
        "simple-moments",
        momentsSinkSchema,
        loadBaseKafkaProperties)

    producerCfg.setLogFailuresOnly(false)
    producerCfg.setFlushOnCheckpoint(true)

    // execute the job
    env.execute("The Proteus Job")
  }

  def printUsage = {
    System.out.println("The Flink Kafka Job")
    System.out.println("Parameters:")
    System.out.println("--bootstrap-server\tKafka Bootstrap Server")
    System.out.println("--state-backend\tFlink State Backend [memory|rocksdb]")
    System.out.println("--state-backend-mbsize\tFlink Memory State Backend size in MB (default: 20)")
    System.out.println("--flink-checkpoints-interval\tFlink Checkpoints Interval in mins (default: 10)")
    System.out.println("--flink-checkpoints-dir\tAn HDFS dir that " +
      "stores rocksdb checkpoints, e.g., hdfs://namenode:9000/flink-checkpoints/")

  }

  def main(args: Array[String]): Unit = {

    var parameters: ParameterTool = null
    try {
      parameters = ParameterTool.fromArgs(args)
      kafkaBootstrapServer = parameters.getRequired("bootstrap-server")

      jobStackBackendType = parameters.get("state-backend")
      assert(jobStackBackendType == "memory" || jobStackBackendType == "rocksdb")

      if (jobStackBackendType == "rocksdb") {
        flinkCheckpointsDir = parameters.getRequired("flink-checkpoints-dir")
      }

      if (jobStackBackendType == "memory") {
        memoryBackendMBSize = parameters.getInt("state-backend-mbsize", 20)
      }

      flinkCheckpointsInterval = parameters.getInt("flink-checkpoints-interval", 10)

    } catch {
      case t: Throwable =>
        LOG.error("Error parsing the command line!")
        printUsage
        System.exit(-1)
    }

    try {
      startProteusJob(parameters)
    } catch {
      case t: Throwable =>
        LOG.error("Failed to run the Proteus Flink Job!")
        LOG.error(t.getMessage, t)
    }

  }

}
