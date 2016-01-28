/*
 * Copyright 2016 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.spark.sql

import java.util

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import org.apache.spark.SparkContext
import org.apache.spark.sql.catalyst.analysis.HiveTypeCoercion
import org.apache.spark.sql.types._

import org.bson._
import com.mongodb.spark.rdd.MongoRDD
import com.mongodb.spark.sql.types.{ConflictType, SkipFieldType}

object MongoInferSchema {

  /**
   * Gets a schema for the specified mongo collection. It is required that the
   * collection provides Documents.
   *
   * Utilizes the `\$sample` aggregation operator, available in server versions 3.2+.
   *
   * @param sc                 the spark context
   * @return the schema for the collection
   */
  def apply(sc: SparkContext): StructType = apply(MongoRDD[BsonDocument](sc))

  /**
   * Gets a schema for the specified mongo collection. It is required that the
   * collection provides Documents.
   *
   * Utilizes the `\$sample` aggregation operator, available in server versions 3.2+.
   *
   * @param mongoRDD           the MongoRDD to be sampled
   * @return the schema for the collection
   */
  def apply(mongoRDD: MongoRDD[BsonDocument]): StructType = {

    // TODO handle pre 3.2
    val schemaData = mongoRDD.withPipeline(List(new Document("$sample", new Document("size", mongoRDD.readConfig.sampleSize))))

    // perform schema inference on each row and merge afterwards
    val rootType: DataType = schemaData
      .mapPartitions(_.map(doc => getSchemaFromDocument(doc)))
      .treeAggregate[DataType](StructType(Seq()))(compatibleType, compatibleType)

    canonicalizeType(rootType) match {
      case Some(st: StructType) => st
      case _                    => StructType(Seq()) // canonicalizeType erases all empty structs, including the only one we want to keep
    }
  }

  /**
   * Remove StructTypes with no fields or SkipFields
   */
  private def canonicalizeType: DataType => Option[DataType] = {
    case at @ ArrayType(elementType, _) =>
      for {
        canonicalType <- canonicalizeType(elementType)
      } yield {
        at.copy(canonicalType)
      }

    case StructType(fields) =>
      val canonicalFields = for {
        field <- fields
        if field.name.nonEmpty
        if field.dataType != SkipFieldType
        canonicalType <- canonicalizeType(field.dataType)
      } yield {
        field.copy(dataType = canonicalType)
      }

      if (canonicalFields.nonEmpty) {
        Some(StructType(canonicalFields))
      } else {
        // per SPARK-8093: empty structs should be deleted
        None
      }
    case other => Some(other)
  }

  private def getSchemaFromDocument(document: BsonDocument): StructType = {
    val fields = new util.ArrayList[StructField]()
    document.entrySet.asScala.foreach(kv => fields.add(DataTypes.createStructField(kv.getKey, getDataType(kv.getValue), true)))
    DataTypes.createStructType(fields)
  }

  /**
   * Gets the matching DataType for the input DataTypes.
   *
   * For simple types, returns a ConflictType if the DataTypes do not match.
   *
   * For complex types:
   * - ArrayTypes: if the DataTypes of the elements cannot be matched, then
   * an ArrayType(ConflictType, true) is returned.
   * - StructTypes: for any field on which the DataTypes conflict, the field
   * value is replaced with a ConflictType.
   *
   * @param t1 the DataType of the first element
   * @param t2 the DataType of the second element
   * @return the DataType that matches on the input DataTypes
   */
  private def compatibleType(t1: DataType, t2: DataType): DataType = {
    HiveTypeCoercion.findTightestCommonTypeOfTwo(t1, t2).getOrElse {
      // t1 or t2 is a StructType, ArrayType, or an unexpected type.
      (t1, t2) match {
        case (StructType(fields1), StructType(fields2)) => {
          val newFields = (fields1 ++ fields2).groupBy(field => field.name).map {
            case (name, fieldTypes) =>
              val dataType = fieldTypes.view.map(_.dataType).reduce(compatibleType)
              StructField(name, dataType, nullable = true)
          }
          StructType(newFields.toSeq.sortBy(_.name))
        }
        case (ArrayType(elementType1, containsNull1), ArrayType(elementType2, containsNull2)) =>
          ArrayType(compatibleType(elementType1, elementType2), containsNull1 || containsNull2)
        // SkipFieldType Types
        case (s: SkipFieldType, dataType: DataType) => dataType
        case (dataType: DataType, s: SkipFieldType) => dataType
        // Conflicting Types
        case (_, _)                                 => ConflictType
      }
    }
  }

  // scalastyle:off cyclomatic.complexity null
  private def getDataType(bsonValue: BsonValue): DataType = {
    bsonValue.getBsonType match {
      case BsonType.NULL      => DataTypes.NullType
      case BsonType.ARRAY     => getSchemaFromArray(bsonValue.asArray().asScala)
      case BsonType.BINARY    => DataTypes.BinaryType
      case BsonType.BOOLEAN   => DataTypes.BooleanType
      case BsonType.DATE_TIME => DataTypes.DateType
      case BsonType.DOCUMENT  => getSchemaFromDocument(bsonValue.asDocument())
      case BsonType.DOUBLE    => DataTypes.DoubleType
      case BsonType.INT32     => DataTypes.IntegerType
      case BsonType.INT64     => DataTypes.LongType
      case BsonType.OBJECT_ID => DataTypes.StringType
      case BsonType.STRING    => DataTypes.StringType
      case BsonType.TIMESTAMP => DataTypes.TimestampType
      case _                  => ConflictType
    }
  }

  @tailrec
  private def getSchemaFromArray(bsonArray: Seq[BsonValue]): DataType = {
    val arrayTypes: Seq[BsonType] = bsonArray.map(_.getBsonType).distinct
    arrayTypes.length match {
      case 0 => SkipFieldType
      case 1 if Seq(BsonType.ARRAY, BsonType.DOCUMENT).contains(arrayTypes.head) => {
        var arrayType: Option[DataType] = None
        bsonArray.takeWhile((bsonValue: BsonValue) => {
          val previous: Option[DataType] = arrayType
          arrayType = Some(getDataType(bsonValue))
          previous.isEmpty match {
            case true => true
            case false => {
              val areEqual: Boolean = arrayType == previous
              if (!areEqual) arrayType = Some(ConflictType)
              areEqual
            }
          }
        })
        arrayType.get match {
          case SkipFieldType => SkipFieldType
          case ConflictType  => ConflictType
          case dataType      => DataTypes.createArrayType(dataType, true)
        }
      }
      case 1 => DataTypes.createArrayType(getDataType(bsonArray.head), true)
      case 2 if arrayTypes.contains(BsonType.NULL) =>
        getSchemaFromArray(bsonArray.filter((bsonValue: BsonValue) => bsonValue.getBsonType == BsonType.NULL))
      case _ => ConflictType
    }
  }
  // scalastyle:on cyclomatic.complexity null

}
