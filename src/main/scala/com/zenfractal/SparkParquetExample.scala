package com.zenfractal

import com.esotericsoftware.kryo.Kryo
import com.twitter.chill.avro.AvroSerializer
import org.apache.spark.serializer.KryoRegistrator
import parquet.hadoop.{ParquetOutputFormat, ParquetInputFormat}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.hadoop.mapreduce.Job
import parquet.avro.{AvroParquetOutputFormat, AvroWriteSupport, AvroReadSupport}
import parquet.filter.{RecordFilter, UnboundRecordFilter}
import java.lang.Iterable
import parquet.column.ColumnReader
import parquet.filter.ColumnRecordFilter._
import parquet.filter.ColumnPredicates._
import com.google.common.io.Files
import java.io.File

object SparkParquetExample {

  // This predicate will remove all amino acids that are not basic
  class BasicAminoAcidPredicate extends UnboundRecordFilter {
    def bind(readers: Iterable[ColumnReader]): RecordFilter = {
      column("type", equalTo(AminoAcidType.BASIC)).bind(readers)
    }
  }

  // Only prints non-null amino acids
  private def aminoAcidPrinter(tuple: Tuple2[Void, AminoAcid]) = {
    if (tuple._2 != null) println(tuple._2)
  }

  // Register generated Avro classes for Spark's internal serialisation
  class AminoAcidKryoRegistrator extends KryoRegistrator {
    override def registerClasses(kryo: Kryo) {
      kryo.register(classOf[AminoAcid], AvroSerializer.SpecificRecordBinarySerializer[AminoAcid])
      // kryo.register(....)
    }
  }

  def main(args: Array[String]) {
    val job = Job.getInstance()

    val sparkConf = new SparkConf()
      .setMaster("local")
      .setAppName("ParquetExample")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", classOf[AminoAcidKryoRegistrator].getName)
    val sc = new SparkContext(sparkConf)

    val tempDir = Files.createTempDir()
    val outputDir = new File(tempDir, "output").getAbsolutePath
    println(outputDir)

    val essentialAminoAcids = List(
      new AminoAcid(AminoAcidType.BASIC, "histidine", "his", 155.16f),
      new AminoAcid(AminoAcidType.ALIPHATIC, "isoleucine", "ile", 131.18f),
      new AminoAcid(AminoAcidType.ALIPHATIC, "leucine", "leu", 131.18f),
      new AminoAcid(AminoAcidType.BASIC, "lysine", "lys", 146.19f),
      new AminoAcid(AminoAcidType.HYDROXYL, "methionine", "met", 149.21f),
      new AminoAcid(AminoAcidType.AROMATIC, "phenylalanine", "phe", 165.19f),
      new AminoAcid(AminoAcidType.HYDROXYL, "threonine", "thr", 119.12f),
      new AminoAcid(AminoAcidType.AROMATIC, "tryptophan", "trp", 204.23f),
      new AminoAcid(AminoAcidType.ALIPHATIC, "valine", "val", 117.15f))

    // Configure the ParquetOutputFormat to use Avro as the serialization format
    ParquetOutputFormat.setWriteSupportClass(job, classOf[AvroWriteSupport])
    // You need to pass the schema to AvroParquet when you are writing objects but not when you
    // are reading them. The schema is saved in Parquet file for future readers to use.
    AvroParquetOutputFormat.setSchema(job, AminoAcid.SCHEMA$)
    // Create a PairRDD with all keys set to null
    val rdd = sc.makeRDD(essentialAminoAcids.map(acid => (null, acid)))
    // Save the RDD to a Parquet file in our temporary output directory
    rdd.saveAsNewAPIHadoopFile(outputDir, classOf[Void], classOf[AminoAcid],
      classOf[ParquetOutputFormat[AminoAcid]], job.getConfiguration)

    // Read all the amino acids back to show that they were all saved to the Parquet file
    ParquetInputFormat.setReadSupportClass(job, classOf[AvroReadSupport[AminoAcid]])
    val file = sc.newAPIHadoopFile(outputDir, classOf[ParquetInputFormat[AminoAcid]],
      classOf[Void], classOf[AminoAcid], job.getConfiguration)
    file.foreach(aminoAcidPrinter)

    // Set a predicate and Parquet only deserializes amino acids that are basic.
    // Non-basic amino acids will returned as null.
    ParquetInputFormat.setUnboundRecordFilter(job, classOf[BasicAminoAcidPredicate])
    val filteredFile = sc.newAPIHadoopFile(outputDir, classOf[ParquetInputFormat[AminoAcid]],
      classOf[Void], classOf[AminoAcid], job.getConfiguration)
    filteredFile.foreach(aminoAcidPrinter)
  }

}
