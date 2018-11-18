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
package org.apache.spark.sql.internal

import scala.util.control.NonFatal
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.api.java._
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry._
import org.apache.spark.sql.catalyst.expressions.aggregate.DeclarativeAggregate
import org.apache.spark.sql.catalyst.expressions.{Expression, ScalaUDF}
import org.apache.spark.sql.execution.aggregate.ScalaUDAF
import org.apache.spark.sql.expressions.UserDefinedAggregateFunction
import org.apache.spark.sql.types.DataType

import scala.util.{Failure, Success, Try}


object UDFFunction {

  def makeFunctionBuilder(name: String, clazz: Class[_] , returnType: DataType): FunctionBuilder = {
    (children: Seq[Expression]) => {
      try {

        if (classOf[UserDefinedAggregateFunction].isAssignableFrom(clazz)) {
          val udaf = clazz.newInstance().asInstanceOf[UserDefinedAggregateFunction]
          ScalaUDAF(children, udaf)
        } else if (classOf[DeclarativeAggregate].isAssignableFrom(clazz)) {
          val ctor = clazz.getDeclaredConstructor(classOf[Seq[_]])
          Try(ctor.newInstance(children).asInstanceOf[Expression]) match {
            case Success(e) => e
            case Failure(e) => {
                 val analysisException = new AnalysisException(e.getCause.getMessage)
                 analysisException.setStackTrace(e.getStackTrace)
	    	 throw analysisException
            }
          }
        } else {
          children.size match {
            // scalastyle:off line.size.limit

            /** This piece of has been generated by this script
                     (1 to 22).map { x =>
                            val numGenerics = x +1
                            val anys = (1 to numGenerics).map(x => "Any").reduce(_ + ", " + _)
                            val params = (1 to x).map(x => "_ :Any").reduce(_ + ", " + _)

                            s"""case $x =>
                                 val func = clazz.newInstance().asInstanceOf[UDF$x[$anys]]
                                 ScalaUDF(func.call($params), returnType, children)
                            """
                           }.foreach(println)

              */

            // Script code starts
            case 1 =>
              val func = clazz.newInstance().asInstanceOf[UDF1[Any, Any]]
              ScalaUDF(func.call(_: Any), returnType, children)

            case 2 =>
              val func = clazz.newInstance().asInstanceOf[UDF2[Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any), returnType, children)

            case 3 =>
              val func = clazz.newInstance().asInstanceOf[UDF3[Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any), returnType, children)

            case 4 =>
              val func = clazz.newInstance().asInstanceOf[UDF4[Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any), returnType, children)

            case 5 =>
              val func = clazz.newInstance().asInstanceOf[UDF5[Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 6 =>
              val func = clazz.newInstance().asInstanceOf[UDF6[Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 7 =>
              val func = clazz.newInstance().asInstanceOf[UDF7[Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 8 =>
              val func = clazz.newInstance().asInstanceOf[UDF8[Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 9 =>
              val func = clazz.newInstance().asInstanceOf[UDF9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 10 =>
              val func = clazz.newInstance().asInstanceOf[UDF10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 11 =>
              val func = clazz.newInstance().asInstanceOf[UDF11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 12 =>
              val func = clazz.newInstance().asInstanceOf[UDF12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 13 =>
              val func = clazz.newInstance().asInstanceOf[UDF13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 14 =>
              val func = clazz.newInstance().asInstanceOf[UDF14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 15 =>
              val func = clazz.newInstance().asInstanceOf[UDF15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 16 =>
              val func = clazz.newInstance().asInstanceOf[UDF16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 17 =>
              val func = clazz.newInstance().asInstanceOf[UDF17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 18 =>
              val func = clazz.newInstance().asInstanceOf[UDF18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 19 =>
              val func = clazz.newInstance().asInstanceOf[UDF19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 20 =>
              val func = clazz.newInstance().asInstanceOf[UDF20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 21 =>
              val func = clazz.newInstance().asInstanceOf[UDF21[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            case 22 =>
              val func = clazz.newInstance().asInstanceOf[UDF22[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
              ScalaUDF(func.call(_: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any, _: Any), returnType, children)

            //Script code end
            // scalastyle:on line.size.limit
            case _ => throw new AnalysisException(s"No handler for SnappyStore UDF '${clazz.getCanonicalName}'")
          }

        }

      } catch {
        case ae: AnalysisException =>
          throw ae
        case NonFatal(e) =>
          val analysisException =
            new AnalysisException(s"No handler for SnappyStore UDF '${clazz.getCanonicalName}': $e")
          analysisException.setStackTrace(e.getStackTrace)
          throw analysisException
      }
    }
  }
}
