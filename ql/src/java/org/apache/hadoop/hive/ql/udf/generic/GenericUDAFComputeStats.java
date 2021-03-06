/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.udf.generic;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.util.JavaDataModel;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.*;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.StringUtils;

/**
 * GenericUDAFComputeStats
 *
 */
@Description(name = "compute_stats",
      value = "_FUNC_(x) - Returns the statistical summary of a set of primitive type values.")
public class GenericUDAFComputeStats extends AbstractGenericUDAFResolver {

  static final Logger LOG = LoggerFactory.getLogger(GenericUDAFComputeStats.class.getName());

  @Override
  public GenericUDAFEvaluator getEvaluator(TypeInfo[] parameters)
      throws SemanticException {
    if (parameters.length != 2 ) {
      throw new UDFArgumentTypeException(parameters.length - 1,
          "Exactly two arguments are expected.");
    }

    if (parameters[0].getCategory() != ObjectInspector.Category.PRIMITIVE) {
      throw new UDFArgumentTypeException(0,
          "Only primitive type arguments are accepted but "
          + parameters[0].getTypeName() + " is passed.");
    }
    switch (((PrimitiveTypeInfo) parameters[0]).getPrimitiveCategory()) {
    case BOOLEAN:
      return new GenericUDAFBooleanStatsEvaluator();
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
    case TIMESTAMP:
      return new GenericUDAFLongStatsEvaluator();
    case FLOAT:
    case DOUBLE:
      return new GenericUDAFDoubleStatsEvaluator();
    case STRING:
    case CHAR:
    case VARCHAR:
      return new GenericUDAFStringStatsEvaluator();
    case BINARY:
      return new GenericUDAFBinaryStatsEvaluator();
    case DECIMAL:
      return new GenericUDAFDecimalStatsEvaluator();
    case DATE:
      return new GenericUDAFDateStatsEvaluator();
    default:
      throw new UDFArgumentTypeException(0,
          "Only integer/long/timestamp/date/float/double/string/binary/boolean/decimal type argument " +
          "is accepted but "
          + parameters[0].getTypeName() + " is passed.");
    }
  }

  /**
   * GenericUDAFBooleanStatsEvaluator.
   *
   */
  public static class GenericUDAFBooleanStatsEvaluator extends GenericUDAFEvaluator {

    /* Object Inspector corresponding to the input parameter.
     */
    private transient PrimitiveObjectInspector inputOI;

    /* Partial aggregation result returned by TerminatePartial. Partial result is a struct
     * containing a long field named "count".
     */
    private transient Object[] partialResult;

    /* Object Inspectors corresponding to the struct returned by TerminatePartial and the long
     * field within the struct - "count"
     */
    private transient StructObjectInspector soi;

    private transient StructField countTruesField;
    private transient WritableLongObjectInspector countTruesFieldOI;

    private transient StructField countFalsesField;
    private transient WritableLongObjectInspector countFalsesFieldOI;

    private transient StructField countNullsField;
    private transient WritableLongObjectInspector countNullsFieldOI;

    /* Output of final result of the aggregation
     */
    private transient Object[] result;

    @Override
    public ObjectInspector init(Mode m, ObjectInspector[] parameters)
        throws HiveException {
       super.init(m, parameters);

      // initialize input
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        inputOI = (PrimitiveObjectInspector) parameters[0];
      } else {
        soi = (StructObjectInspector) parameters[0];

        countTruesField = soi.getStructFieldRef("counttrues");
        countTruesFieldOI = (WritableLongObjectInspector)
                               countTruesField.getFieldObjectInspector();

        countFalsesField = soi.getStructFieldRef("countfalses");
        countFalsesFieldOI = (WritableLongObjectInspector)
                                countFalsesField.getFieldObjectInspector();

        countNullsField = soi.getStructFieldRef("countnulls");
        countNullsFieldOI = (WritableLongObjectInspector) countNullsField.getFieldObjectInspector();
      }

      // initialize output
      List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
      foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
      foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
      foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
      foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);

      List<String> fname = new ArrayList<String>();
      fname.add("columntype");
      fname.add("counttrues");
      fname.add("countfalses");
      fname.add("countnulls");

      partialResult = new Object[4];
      partialResult[0] = new Text();
      partialResult[1] = new LongWritable(0);
      partialResult[2] = new LongWritable(0);
      partialResult[3] = new LongWritable(0);

      result = new Object[4];
      result[0] = new Text();
      result[1] = new LongWritable(0);
      result[2] = new LongWritable(0);
      result[3] = new LongWritable(0);

      return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
            foi);
    }

    @AggregationType(estimable = true)
    public static class BooleanStatsAgg extends AbstractAggregationBuffer {
      public String columnType;                        /* Datatype of column */
      public long countTrues;  /* Count of number of true values seen so far */
      public long countFalses; /* Count of number of false values seen so far */
      public long countNulls;  /* Count of number of null values seen so far */
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return model.primitive2() * 3 + model.lengthFor(columnType);
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      BooleanStatsAgg result = new BooleanStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      BooleanStatsAgg myagg = (BooleanStatsAgg) agg;
      myagg.columnType = new String("Boolean");
      myagg.countTrues = 0;
      myagg.countFalses = 0;
      myagg.countNulls = 0;
    }

    private void printDebugOutput(String functionName, AggregationBuffer agg) {
      BooleanStatsAgg myagg = (BooleanStatsAgg) agg;

      LOG.debug(functionName);
      LOG.debug("Count of True Values: {}", myagg.countTrues);
      LOG.debug("Count of False Values: {}", myagg.countFalses);
      LOG.debug("Count of Null Values: {}", myagg.countNulls);
   }

    boolean warned = false;

    @Override
    public void iterate(AggregationBuffer agg, Object[] parameters) throws HiveException {
      Object p = parameters[0];
      BooleanStatsAgg myagg = (BooleanStatsAgg) agg;
      boolean emptyTable = false;

      if (parameters[1] == null) {
        emptyTable = true;
      }

      if (!emptyTable) {
        if (p == null) {
          myagg.countNulls++;
        }
        else {
          try {
            boolean v = PrimitiveObjectInspectorUtils.getBoolean(p, inputOI);
            if (v == false) {
              myagg.countFalses++;
            } else if (v == true){
              myagg.countTrues++;
            }
          } catch (NumberFormatException e) {
            if (!warned) {
              warned = true;
              LOG.warn(getClass().getSimpleName() + " "
                  + StringUtils.stringifyException(e));
              LOG.warn(getClass().getSimpleName()
                  + " ignoring similar exceptions.");
            }
          }
        }
      }
    }

    @Override
    public Object terminatePartial(AggregationBuffer agg) throws HiveException {
      BooleanStatsAgg myagg = (BooleanStatsAgg) agg;
      ((Text) partialResult[0]).set(myagg.columnType);
      ((LongWritable) partialResult[1]).set(myagg.countTrues);
      ((LongWritable) partialResult[2]).set(myagg.countFalses);
      ((LongWritable) partialResult[3]).set(myagg.countNulls);
      return partialResult;
    }

    @Override
    public void merge(AggregationBuffer agg, Object partial) throws HiveException {
      if (partial != null) {
        BooleanStatsAgg myagg = (BooleanStatsAgg) agg;

        Object partialCount = soi.getStructFieldData(partial, countTruesField);
        myagg.countTrues += countTruesFieldOI.get(partialCount);

        partialCount = soi.getStructFieldData(partial, countFalsesField);
        myagg.countFalses += countFalsesFieldOI.get(partialCount);

        partialCount = soi.getStructFieldData(partial, countNullsField);
        myagg.countNulls += countNullsFieldOI.get(partialCount);
      }
    }

    @Override
    public Object terminate(AggregationBuffer agg) throws HiveException {
      BooleanStatsAgg myagg = (BooleanStatsAgg) agg;
      ((Text)result[0]).set(myagg.columnType);
      ((LongWritable)result[1]).set(myagg.countTrues);
      ((LongWritable)result[2]).set(myagg.countFalses);
      ((LongWritable)result[3]).set(myagg.countNulls);
      return result;
    }
  }

  public static abstract class GenericUDAFNumericStatsEvaluator<V, OI extends PrimitiveObjectInspector>
      extends GenericUDAFEvaluator {

    protected final static int MAX_BIT_VECTORS = 1024;

    /* Object Inspector corresponding to the input parameter.
     */
    protected transient PrimitiveObjectInspector inputOI;
    protected transient PrimitiveObjectInspector numVectorsOI;


    /* Object Inspectors corresponding to the struct returned by TerminatePartial and the long
     * field within the struct - "count"
     */
    protected transient StructObjectInspector soi;

    protected transient StructField minField;
    protected transient OI minFieldOI;

    protected transient StructField maxField;
    protected transient OI maxFieldOI;

    protected transient StructField countNullsField;
    protected transient LongObjectInspector countNullsFieldOI;

    protected transient StructField ndvField;
    protected transient StringObjectInspector ndvFieldOI;

    protected transient StructField numBitVectorsField;
    protected transient IntObjectInspector numBitVectorsFieldOI;

    /* Partial aggregation result returned by TerminatePartial. Partial result is a struct
     * containing a long field named "count".
     */
    protected transient Object[] partialResult;

    /* Output of final result of the aggregation
     */
    protected transient Object[] result;

    protected transient boolean warned;

    protected abstract OI getValueObjectInspector();
    
    protected abstract OI getValueObjectInspector(PrimitiveTypeInfo typeInfo);

    @Override
    public ObjectInspector init(Mode m, ObjectInspector[] parameters) throws HiveException {
      super.init(m, parameters);

      // initialize input
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        inputOI = (PrimitiveObjectInspector) parameters[0];
        numVectorsOI = (PrimitiveObjectInspector) parameters[1];
      } else {
        soi = (StructObjectInspector) parameters[0];

        minField = soi.getStructFieldRef("min");
        minFieldOI = (OI) minField.getFieldObjectInspector();

        maxField = soi.getStructFieldRef("max");
        maxFieldOI = (OI) maxField.getFieldObjectInspector();

        countNullsField = soi.getStructFieldRef("countnulls");
        countNullsFieldOI = (LongObjectInspector) countNullsField.getFieldObjectInspector();

        ndvField = soi.getStructFieldRef("bitvector");
        ndvFieldOI = (StringObjectInspector) ndvField.getFieldObjectInspector();

        numBitVectorsField = soi.getStructFieldRef("numbitvectors");
        numBitVectorsFieldOI = (IntObjectInspector)
            numBitVectorsField.getFieldObjectInspector();
      }

      // initialize output
      if (mode == Mode.PARTIAL1 || mode == Mode.PARTIAL2) {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(getValueObjectInspector(inputOI.getTypeInfo()));
        foi.add(getValueObjectInspector(inputOI.getTypeInfo()));
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableIntObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columnType");
        fname.add("min");
        fname.add("max");
        fname.add("countnulls");
        fname.add("bitvector");
        fname.add("numbitvectors");

        partialResult = new Object[6];
        partialResult[0] = new Text();
        partialResult[3] = new LongWritable(0);
        partialResult[4] = new Text();
        partialResult[5] = new IntWritable(0);

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
            foi);
      } else {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(minFieldOI != null ? getValueObjectInspector(minFieldOI.getTypeInfo()) : getValueObjectInspector());
        foi.add(maxFieldOI != null ? getValueObjectInspector(maxFieldOI.getTypeInfo()) : getValueObjectInspector());
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columnType");
        fname.add("min");
        fname.add("max");
        fname.add("countnulls");
        fname.add("numdistinctvalues");
        fname.add("ndvbitvector");

        result = new Object[6];
        result[0] = new Text();
        result[3] = new LongWritable(0);
        result[4] = new LongWritable(0);
        result[5] = new Text();

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
            foi);
      }
    }

    public abstract class NumericStatsAgg extends AbstractAggregationBuffer {

      public String columnType;
      public V min;                              /* Minimum value seen so far */
      public V max;                              /* Maximum value seen so far */
      public long countNulls;                    /* Count of number of null values seen so far */
      public NumDistinctValueEstimator numDV;    /* Distinct value estimator */

      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return model.lengthFor(columnType)
            + model.primitive1()
            + model.primitive2()
            + ((numDV == null) ? NumDistinctValueEstimator.lengthFor(model, null) :
                                 numDV.lengthFor(model));
      }

      protected void initNDVEstimator(int numBitVectors) {
        numDV = new NumDistinctValueEstimator(numBitVectors);
      }

      protected abstract void update(Object p, PrimitiveObjectInspector inputOI);

      protected abstract void updateMin(Object minValue, OI minOI);

      protected abstract void updateMax(Object maxValue, OI maxOI);

      protected Object serialize(Object[] result) {
        serializeCommon(result);
        long dv = numDV != null ? numDV.estimateNumDistinctValues() : 0;
        ((LongWritable) result[4]).set(dv);
        if (numDV != null) {
          ((Text) result[5]).set(numDV.serialize());
        }

        return result;
      }

      protected Object serializePartial(Object[] result) {
        // Serialize the rest of the values in the AggBuffer
        serializeCommon(result);

        // Serialize numDistinctValue Estimator
        Text t = numDV.serialize();
        ((Text) result[4]).set(t);
        ((IntWritable) result[5]).set(numDV.getnumBitVectors());

        return result;
      }

      private void serializeCommon(Object[] result) {
        // Serialize rest of the field in the AggBuffer
        ((Text) result[0]).set(columnType);
        result[1] = min;
        result[2] = max;
        ((LongWritable) result[3]).set(countNulls);
      }

      public void reset(String type) throws HiveException {
        columnType = type;
        min = null;
        max = null;
        countNulls = 0;
        numDV = null;
      }
    };

    @Override
    public void iterate(AggregationBuffer agg, Object[] parameters) throws HiveException {
      NumericStatsAgg myagg = (NumericStatsAgg) agg;

      if (myagg.numDV == null) {
        int numVectors = parameters[1] == null ? 0 :
            PrimitiveObjectInspectorUtils.getInt(parameters[1], numVectorsOI);
        if (numVectors > MAX_BIT_VECTORS) {
          throw new HiveException("The maximum allowed value for number of bit vectors " +
              " is " + MAX_BIT_VECTORS + ", but was passed " + numVectors + " bit vectors");
        }
        myagg.initNDVEstimator(numVectors);
      }

      //Update null counter if a null value is seen
      if (parameters[0] == null) {
        myagg.countNulls++;
      } else {
        try {
          myagg.update(parameters[0], inputOI);
        } catch (NumberFormatException e) {
          if (!warned) {
            warned = true;
            LOG.warn(getClass().getSimpleName() + " "
                + StringUtils.stringifyException(e));
            LOG.warn(getClass().getSimpleName()
                + " ignoring similar exceptions.");
          }
        }
      }
    }

    @Override
    public Object terminatePartial(AggregationBuffer agg) throws HiveException {
      return ((NumericStatsAgg) agg).serializePartial(partialResult);
    }

    @Override
    public Object terminate(AggregationBuffer agg) throws HiveException {
      return ((NumericStatsAgg) agg).serialize(result);
    }

    @Override
    public void merge(AggregationBuffer agg, Object partial) throws HiveException {
      if (partial != null) {
        NumericStatsAgg myagg = (NumericStatsAgg) agg;

        if (myagg.numDV == null) {
          Object partialValue = soi.getStructFieldData(partial, numBitVectorsField);
          int numVectors = numBitVectorsFieldOI.get(partialValue);
          if (numVectors <= 0) {
            return;
          }
          myagg.initNDVEstimator(numVectors);
        }

        // Update min if min is lesser than the smallest value seen so far
        Object minValue = soi.getStructFieldData(partial, minField);
        myagg.updateMin(minValue, minFieldOI);

        // Update max if max is greater than the largest value seen so far
        Object maxValue = soi.getStructFieldData(partial, maxField);
        myagg.updateMax(maxValue, maxFieldOI);

        // Update the null counter
        Object countNull = soi.getStructFieldData(partial, countNullsField);
        myagg.countNulls += countNullsFieldOI.get(countNull);

        // Merge numDistinctValue Estimators
        Object numDistinct = soi.getStructFieldData(partial, ndvField);
        String v = ndvFieldOI.getPrimitiveJavaObject(numDistinct);
        NumDistinctValueEstimator o =
            new NumDistinctValueEstimator(v, myagg.numDV.getnumBitVectors());
        myagg.numDV.mergeEstimators(o);
      }
    }
  }

  /**
   * GenericUDAFLongStatsEvaluator.
   *
   */
  public static class GenericUDAFLongStatsEvaluator
      extends GenericUDAFNumericStatsEvaluator<Long, LongObjectInspector> {

    @Override
    protected LongObjectInspector getValueObjectInspector() {
      return PrimitiveObjectInspectorFactory.javaLongObjectInspector;
    }

    @Override
    protected LongObjectInspector getValueObjectInspector(PrimitiveTypeInfo typeInfo) {
      return getValueObjectInspector();
    }

    @AggregationType(estimable = true)
    public class LongStatsAgg extends NumericStatsAgg {
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return super.estimate() + model.primitive2() * 2;
      }

      @Override
      protected void update(Object p, PrimitiveObjectInspector inputOI) {
        long v = PrimitiveObjectInspectorUtils.getLong(p, inputOI);
        //Update min counter if new value is less than min seen so far
        if (min == null || v < min) {
          min = v;
        }
        //Update max counter if new value is greater than max seen so far
        if (max == null || v > max) {
          max = v;
        }
        // Add value to NumDistinctValue Estimator
        numDV.addToEstimator(v);
      }

      @Override
      protected void updateMin(Object minValue, LongObjectInspector minFieldOI) {
        if ((minValue != null) && (min == null || (min > minFieldOI.get(minValue)))) {
          min = minFieldOI.get(minValue);
        }
      }

      @Override
      protected void updateMax(Object maxValue, LongObjectInspector maxFieldOI) {
        if ((maxValue != null ) && (max == null || (max < maxFieldOI.get(maxValue)))) {
          max = maxFieldOI.get(maxValue);
        }
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      AggregationBuffer result = new LongStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      ((NumericStatsAgg)agg).reset("Long");
    }
  }

  /**
   * GenericUDAFDoubleStatsEvaluator.
   *
   */
  public static class GenericUDAFDoubleStatsEvaluator
      extends GenericUDAFNumericStatsEvaluator<Double, DoubleObjectInspector> {

    @Override
    protected DoubleObjectInspector getValueObjectInspector() {
      return PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
    }

    @Override
    protected DoubleObjectInspector getValueObjectInspector(PrimitiveTypeInfo typeInfo) {
      return getValueObjectInspector();
    }

    @AggregationType(estimable = true)
    public class DoubleStatsAgg extends NumericStatsAgg {
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return super.estimate() + model.primitive2() * 2;
      }

      @Override
      protected void update(Object p, PrimitiveObjectInspector inputOI) {
        double v = PrimitiveObjectInspectorUtils.getDouble(p, inputOI);
        //Update min counter if new value is less than min seen so far
        if (min == null || v < min) {
          min = v;
        }
        //Update max counter if new value is greater than max seen so far
        if (max == null || v > max) {
          max = v;
        }
        // Add value to NumDistinctValue Estimator
        numDV.addToEstimator(v);
      }

      @Override
      protected void updateMin(Object minValue, DoubleObjectInspector minFieldOI) {
        if ((minValue != null) && (min == null || (min > minFieldOI.get(minValue)))) {
          min = minFieldOI.get(minValue);
        }
      }

      @Override
      protected void updateMax(Object maxValue, DoubleObjectInspector maxFieldOI) {
        if ((maxValue != null ) && (max == null || (max < maxFieldOI.get(maxValue)))) {
          max = maxFieldOI.get(maxValue);
        }
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      AggregationBuffer result = new DoubleStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      ((NumericStatsAgg)agg).reset("Double");
    }
  }

  /**
   * GenericUDAFStringStatsEvaluator.
   *
   */
  public static class GenericUDAFStringStatsEvaluator extends GenericUDAFEvaluator {

    /* Object Inspector corresponding to the input parameter.
     */
    private transient PrimitiveObjectInspector inputOI;
    private transient PrimitiveObjectInspector numVectorsOI;
    private final static int MAX_BIT_VECTORS = 1024;

    /* Partial aggregation result returned by TerminatePartial. Partial result is a struct
     * containing a long field named "count".
     */
    private transient Object[] partialResult;

    /* Object Inspectors corresponding to the struct returned by TerminatePartial and the
     * fields within the struct - "maxLength", "sumLength", "count", "countNulls", "ndv"
     */
    private transient StructObjectInspector soi;

    private transient StructField maxLengthField;
    private transient LongObjectInspector maxLengthFieldOI;

    private transient StructField sumLengthField;
    private transient LongObjectInspector sumLengthFieldOI;

    private transient StructField countField;
    private transient LongObjectInspector countFieldOI;

    private transient StructField countNullsField;
    private transient LongObjectInspector countNullsFieldOI;

    private transient StructField ndvField;
    private transient StringObjectInspector ndvFieldOI;

    private transient StructField numBitVectorsField;
    private transient IntObjectInspector numBitVectorsFieldOI;

    /* Output of final result of the aggregation
     */
    private transient Object[] result;

    @Override
    public ObjectInspector init(Mode m, ObjectInspector[] parameters) throws HiveException {
      super.init(m, parameters);

      // initialize input
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        inputOI = (PrimitiveObjectInspector) parameters[0];
        numVectorsOI = (PrimitiveObjectInspector) parameters[1];
      } else {
        soi = (StructObjectInspector) parameters[0];

        maxLengthField = soi.getStructFieldRef("maxlength");
        maxLengthFieldOI = (LongObjectInspector) maxLengthField.getFieldObjectInspector();

        sumLengthField = soi.getStructFieldRef("sumlength");
        sumLengthFieldOI = (LongObjectInspector) sumLengthField.getFieldObjectInspector();

        countField = soi.getStructFieldRef("count");
        countFieldOI = (LongObjectInspector) countField.getFieldObjectInspector();

        countNullsField = soi.getStructFieldRef("countnulls");
        countNullsFieldOI = (LongObjectInspector) countNullsField.getFieldObjectInspector();

        ndvField = soi.getStructFieldRef("bitvector");
        ndvFieldOI = (StringObjectInspector) ndvField.getFieldObjectInspector();

        numBitVectorsField = soi.getStructFieldRef("numbitvectors");
        numBitVectorsFieldOI = (IntObjectInspector)
                                  numBitVectorsField.getFieldObjectInspector();
      }

      // initialize output
      if (mode == Mode.PARTIAL1 || mode == Mode.PARTIAL2) {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableIntObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columntype");
        fname.add("maxlength");
        fname.add("sumlength");
        fname.add("count");
        fname.add("countnulls");
        fname.add("bitvector");
        fname.add("numbitvectors");

        partialResult = new Object[7];
        partialResult[0] = new Text();
        partialResult[1] = new LongWritable(0);
        partialResult[2] = new LongWritable(0);
        partialResult[3] = new LongWritable(0);
        partialResult[4] = new LongWritable(0);
        partialResult[5] = new Text();
        partialResult[6] = new IntWritable(0);

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
          foi);
      } else {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columntype");
        fname.add("maxlength");
        fname.add("avglength");
        fname.add("countnulls");
        fname.add("numdistinctvalues");
        fname.add("ndvbitvector");

        result = new Object[6];
        result[0] = new Text();
        result[1] = new LongWritable(0);
        result[2] = new DoubleWritable(0);
        result[3] = new LongWritable(0);
        result[4] = new LongWritable(0);
        result[5] = new Text();

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
            foi);
      }
    }

    @AggregationType(estimable = true)
    public static class StringStatsAgg extends AbstractAggregationBuffer {
      public String columnType;
      public long maxLength;                           /* Maximum length seen so far */
      public long sumLength;             /* Sum of lengths of all values seen so far */
      public long count;                          /* Count of all values seen so far */
      public long countNulls;          /* Count of number of null values seen so far */
      public StringNumDistinctValueEstimator numDV;      /* Distinct value estimator */
      public int numBitVectors;
      public boolean firstItem;
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return model.primitive1() * 2 + model.primitive2() * 4 +
            model.lengthFor(columnType) +
            ((numDV == null) ? NumDistinctValueEstimator.lengthFor(model, null) :
                               numDV.lengthFor(model));

      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      StringStatsAgg result = new StringStatsAgg();
      reset(result);
      return result;
    }

    public void initNDVEstimator(StringStatsAgg aggBuffer, int numBitVectors) {
      aggBuffer.numDV = new StringNumDistinctValueEstimator(numBitVectors);
      aggBuffer.numDV.reset();
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      StringStatsAgg myagg = (StringStatsAgg) agg;
      myagg.columnType = new String("String");
      myagg.maxLength = 0;
      myagg.sumLength = 0;
      myagg.count = 0;
      myagg.countNulls = 0;
      myagg.firstItem = true;
    }

    boolean warned = false;

    @Override
    public void iterate(AggregationBuffer agg, Object[] parameters) throws HiveException {
      Object p = parameters[0];
      StringStatsAgg myagg = (StringStatsAgg) agg;
      boolean emptyTable = false;

      if (parameters[1] == null) {
        emptyTable = true;
      }

      if (myagg.firstItem) {
        int numVectors = 0;
        if (!emptyTable) {
          numVectors = PrimitiveObjectInspectorUtils.getInt(parameters[1], numVectorsOI);
        }

        if (numVectors > MAX_BIT_VECTORS) {
          throw new HiveException("The maximum allowed value for number of bit vectors " +
            " is " + MAX_BIT_VECTORS + " , but was passed " + numVectors + " bit vectors");
        }

        initNDVEstimator(myagg, numVectors);
        myagg.firstItem = false;
        myagg.numBitVectors = numVectors;
      }

      if (!emptyTable) {

        // Update null counter if a null value is seen
        if (p == null) {
          myagg.countNulls++;
        }
        else {
          try {

            String v = PrimitiveObjectInspectorUtils.getString(p, inputOI);

            // Update max length if new length is greater than the ones seen so far
            int len = v.length();
            if (len > myagg.maxLength) {
              myagg.maxLength = len;
            }

            // Update sum length with the new length
            myagg.sumLength += len;

            // Increment count of values seen so far
            myagg.count++;

            // Add string value to NumDistinctValue Estimator
            myagg.numDV.addToEstimator(v);

          } catch (NumberFormatException e) {
            if (!warned) {
              warned = true;
              LOG.warn(getClass().getSimpleName() + " "
                  + StringUtils.stringifyException(e));
              LOG.warn(getClass().getSimpleName()
                  + " ignoring similar exceptions.");
            }
          }
        }
      }
    }

    @Override
    public Object terminatePartial(AggregationBuffer agg) throws HiveException {
      StringStatsAgg myagg = (StringStatsAgg) agg;

      // Serialize numDistinctValue Estimator
      Text t = myagg.numDV.serialize();

      // Serialize the rest of the values in the AggBuffer
      ((Text) partialResult[0]).set(myagg.columnType);
      ((LongWritable) partialResult[1]).set(myagg.maxLength);
      ((LongWritable) partialResult[2]).set(myagg.sumLength);
      ((LongWritable) partialResult[3]).set(myagg.count);
      ((LongWritable) partialResult[4]).set(myagg.countNulls);
      ((Text) partialResult[5]).set(t);
      ((IntWritable) partialResult[6]).set(myagg.numBitVectors);

      return partialResult;
    }

    @Override
    public void merge(AggregationBuffer agg, Object partial) throws HiveException {
      if (partial != null) {
        StringStatsAgg myagg = (StringStatsAgg) agg;

        if (myagg.firstItem) {
          Object partialValue = soi.getStructFieldData(partial, numBitVectorsField);
          int numVectors = numBitVectorsFieldOI.get(partialValue);
          if (numVectors <= 0) {
            return;
          }
          initNDVEstimator(myagg, numVectors);
          myagg.firstItem = false;
          myagg.numBitVectors = numVectors;
        }

        // Update maxLength if length is greater than the largest value seen so far
        Object partialValue = soi.getStructFieldData(partial, maxLengthField);
        if (myagg.maxLength < maxLengthFieldOI.get(partialValue)) {
          myagg.maxLength = maxLengthFieldOI.get(partialValue);
        }

        // Update sum of the length of the values seen so far
        partialValue = soi.getStructFieldData(partial, sumLengthField);
        myagg.sumLength += sumLengthFieldOI.get(partialValue);

        // Update the count of the number of values seen so far
        partialValue = soi.getStructFieldData(partial, countField);
        myagg.count += countFieldOI.get(partialValue);

        // Update the null counter
        partialValue = soi.getStructFieldData(partial, countNullsField);
        myagg.countNulls += countNullsFieldOI.get(partialValue);

        // Merge numDistinctValue Estimators
        partialValue = soi.getStructFieldData(partial, ndvField);
        String v = ndvFieldOI.getPrimitiveJavaObject(partialValue);
        NumDistinctValueEstimator o = new NumDistinctValueEstimator(v, myagg.numBitVectors);
        myagg.numDV.mergeEstimators(o);
      }
    }

    @Override
    public Object terminate(AggregationBuffer agg) throws HiveException {
      StringStatsAgg myagg = (StringStatsAgg) agg;

      long numDV = 0;
      double avgLength = 0.0;
      long total = myagg.count + myagg.countNulls;

      if (myagg.numBitVectors != 0) {
        numDV = myagg.numDV.estimateNumDistinctValues();
      }

      if (total != 0) {
         avgLength = myagg.sumLength / (1.0 * total);
      }

      // Serialize the result struct
      ((Text) result[0]).set(myagg.columnType);
      ((LongWritable) result[1]).set(myagg.maxLength);
      ((DoubleWritable) result[2]).set(avgLength);
      ((LongWritable) result[3]).set(myagg.countNulls);
      ((LongWritable) result[4]).set(numDV);
      if (myagg.numBitVectors != 0) {
        ((Text) result[5]).set(myagg.numDV.serialize());
      }
      return result;
    }
  }

  /**
   * GenericUDAFBinaryStatsEvaluator.
   *
   */
  public static class GenericUDAFBinaryStatsEvaluator extends GenericUDAFEvaluator {

    /* Object Inspector corresponding to the input parameter.
     */
    private transient PrimitiveObjectInspector inputOI;

    /* Partial aggregation result returned by TerminatePartial. Partial result is a struct
     * containing a long field named "count".
     */
    private transient Object[] partialResult;

    /* Object Inspectors corresponding to the struct returned by TerminatePartial and the
     * fields within the struct - "maxLength", "sumLength", "count", "countNulls"
     */
    private transient StructObjectInspector soi;

    private transient StructField maxLengthField;
    private transient LongObjectInspector maxLengthFieldOI;

    private transient StructField sumLengthField;
    private transient LongObjectInspector sumLengthFieldOI;

    private transient StructField countField;
    private transient LongObjectInspector countFieldOI;

    private transient StructField countNullsField;
    private transient LongObjectInspector countNullsFieldOI;

    /* Output of final result of the aggregation
     */
    private transient Object[] result;

    @Override
    public ObjectInspector init(Mode m, ObjectInspector[] parameters) throws HiveException {
      super.init(m, parameters);

      // initialize input
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        inputOI = (PrimitiveObjectInspector) parameters[0];
      } else {
        soi = (StructObjectInspector) parameters[0];

        maxLengthField = soi.getStructFieldRef("maxlength");
        maxLengthFieldOI = (LongObjectInspector) maxLengthField.getFieldObjectInspector();

        sumLengthField = soi.getStructFieldRef("sumlength");
        sumLengthFieldOI = (LongObjectInspector) sumLengthField.getFieldObjectInspector();

        countField = soi.getStructFieldRef("count");
        countFieldOI = (LongObjectInspector) countField.getFieldObjectInspector();

        countNullsField = soi.getStructFieldRef("countnulls");
        countNullsFieldOI = (LongObjectInspector) countNullsField.getFieldObjectInspector();

      }

      // initialize output
      if (mode == Mode.PARTIAL1 || mode == Mode.PARTIAL2) {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columntype");
        fname.add("maxlength");
        fname.add("sumlength");
        fname.add("count");
        fname.add("countnulls");

        partialResult = new Object[5];
        partialResult[0] = new Text();
        partialResult[1] = new LongWritable(0);
        partialResult[2] = new LongWritable(0);
        partialResult[3] = new LongWritable(0);
        partialResult[4] = new LongWritable(0);

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
          foi);
      } else {
        List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);

        List<String> fname = new ArrayList<String>();
        fname.add("columntype");
        fname.add("maxlength");
        fname.add("avglength");
        fname.add("countnulls");

        result = new Object[4];
        result[0] = new Text();
        result[1] = new LongWritable(0);
        result[2] = new DoubleWritable(0);
        result[3] = new LongWritable(0);

        return ObjectInspectorFactory.getStandardStructObjectInspector(fname,
            foi);
      }
    }

    @AggregationType(estimable = true)
    public static class BinaryStatsAgg extends AbstractAggregationBuffer {
      public String columnType;
      public long maxLength;                           /* Maximum length seen so far */
      public long sumLength;             /* Sum of lengths of all values seen so far */
      public long count;                          /* Count of all values seen so far */
      public long countNulls;          /* Count of number of null values seen so far */
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return model.primitive2() * 4 + model.lengthFor(columnType);
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      BinaryStatsAgg result = new BinaryStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      BinaryStatsAgg myagg = (BinaryStatsAgg) agg;
      myagg.columnType = new String("Binary");
      myagg.maxLength = 0;
      myagg.sumLength = 0;
      myagg.count = 0;
      myagg.countNulls = 0;
    }

    boolean warned = false;

    @Override
    public void iterate(AggregationBuffer agg, Object[] parameters) throws HiveException {
      Object p = parameters[0];
      BinaryStatsAgg myagg = (BinaryStatsAgg) agg;
      boolean emptyTable = false;

      if (parameters[1] == null) {
        emptyTable = true;
      }

      if (!emptyTable) {
        // Update null counter if a null value is seen
        if (p == null) {
          myagg.countNulls++;
        }
        else {
          try {
            BytesWritable v = PrimitiveObjectInspectorUtils.getBinary(p, inputOI);

            // Update max length if new length is greater than the ones seen so far
            int len = v.getLength();
            if (len > myagg.maxLength) {
              myagg.maxLength = len;
            }

            // Update sum length with the new length
            myagg.sumLength += len;

            // Increment count of values seen so far
            myagg.count++;

          } catch (NumberFormatException e) {
            if (!warned) {
              warned = true;
              LOG.warn(getClass().getSimpleName() + " "
                  + StringUtils.stringifyException(e));
              LOG.warn(getClass().getSimpleName()
                  + " ignoring similar exceptions.");
            }
          }
        }
      }
    }

    @Override
    public Object terminatePartial(AggregationBuffer agg) throws HiveException {
      BinaryStatsAgg myagg = (BinaryStatsAgg) agg;

      // Serialize AggBuffer
      ((Text) partialResult[0]).set(myagg.columnType);
      ((LongWritable) partialResult[1]).set(myagg.maxLength);
      ((LongWritable) partialResult[2]).set(myagg.sumLength);
      ((LongWritable) partialResult[3]).set(myagg.count);
      ((LongWritable) partialResult[4]).set(myagg.countNulls);

      return partialResult;
    }

    @Override
    public void merge(AggregationBuffer agg, Object partial) throws HiveException {
      if (partial != null) {
        BinaryStatsAgg myagg = (BinaryStatsAgg) agg;

        // Update maxLength if length is greater than the largest value seen so far
        Object partialValue = soi.getStructFieldData(partial, maxLengthField);
        if (myagg.maxLength < maxLengthFieldOI.get(partialValue)) {
          myagg.maxLength = maxLengthFieldOI.get(partialValue);
        }

        // Update sum of the length of the values seen so far
        partialValue = soi.getStructFieldData(partial, sumLengthField);
        myagg.sumLength += sumLengthFieldOI.get(partialValue);

        // Update the count of the number of values seen so far
        partialValue = soi.getStructFieldData(partial, countField);
        myagg.count += countFieldOI.get(partialValue);

        // Update the null counter
        partialValue = soi.getStructFieldData(partial, countNullsField);
        myagg.countNulls += countNullsFieldOI.get(partialValue);
      }
    }

    @Override
    public Object terminate(AggregationBuffer agg) throws HiveException {
      BinaryStatsAgg myagg = (BinaryStatsAgg) agg;
      double avgLength = 0.0;
      long count = myagg.count + myagg.countNulls;

      if (count != 0) {
        avgLength = myagg.sumLength / (1.0 * (myagg.count + myagg.countNulls));
      }

      // Serialize the result struct
      ((Text) result[0]).set(myagg.columnType);
      ((LongWritable) result[1]).set(myagg.maxLength);
      ((DoubleWritable) result[2]).set(avgLength);
      ((LongWritable) result[3]).set(myagg.countNulls);

      return result;
    }
  }

  public static class GenericUDAFDecimalStatsEvaluator
      extends GenericUDAFNumericStatsEvaluator<HiveDecimal, HiveDecimalObjectInspector> {

    @Override
    protected HiveDecimalObjectInspector getValueObjectInspector() {
      return PrimitiveObjectInspectorFactory.javaHiveDecimalObjectInspector;
    }

    @Override
    protected HiveDecimalObjectInspector getValueObjectInspector(PrimitiveTypeInfo typeInfo) {
      return (JavaHiveDecimalObjectInspector)PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector(typeInfo);    	
    }
    
    @AggregationType(estimable = true)
    public class DecimalStatsAgg extends NumericStatsAgg {
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return super.estimate() + model.lengthOfDecimal() * 2;
      }

      @Override
      protected void update(Object p, PrimitiveObjectInspector inputOI) {
        HiveDecimal v = PrimitiveObjectInspectorUtils.getHiveDecimal(p, inputOI);
        //Update min counter if new value is less than min seen so far
        if (min == null || v.compareTo(min) < 0) {
          min = v;
        }
        //Update max counter if new value is greater than max seen so far
        if (max == null || v.compareTo(max) > 0) {
          max = v;
        }
        // Add value to NumDistinctValue Estimator
        numDV.addToEstimator(v);
      }

      @Override
      protected void updateMin(Object minValue, HiveDecimalObjectInspector minFieldOI) {
        if ((minValue != null) && (min == null ||
            min.compareTo(minFieldOI.getPrimitiveJavaObject(minValue)) > 0)) {
          min = minFieldOI.getPrimitiveJavaObject(minValue);
        }
      }

      @Override
      protected void updateMax(Object maxValue, HiveDecimalObjectInspector maxFieldOI) {
        if ((maxValue != null) && (max == null ||
            max.compareTo(maxFieldOI.getPrimitiveJavaObject(maxValue)) < 0)) {
          max = maxFieldOI.getPrimitiveJavaObject(maxValue);
        }
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      AggregationBuffer result = new DecimalStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      ((NumericStatsAgg)agg).reset("Decimal");
    }
  }

  /**
   * GenericUDAFDateStatsEvaluator
   * High/low value will be saved in stats DB as long value representing days since epoch.
   */
  public static class GenericUDAFDateStatsEvaluator
      extends GenericUDAFNumericStatsEvaluator<DateWritable, DateObjectInspector> {

    @Override
    protected DateObjectInspector getValueObjectInspector() {
      return PrimitiveObjectInspectorFactory.writableDateObjectInspector;
    }

    @Override
    protected DateObjectInspector getValueObjectInspector(PrimitiveTypeInfo typeInfo) {
      return getValueObjectInspector();
    }

    @AggregationType(estimable = true)
    public class DateStatsAgg extends NumericStatsAgg {
      @Override
      public int estimate() {
        JavaDataModel model = JavaDataModel.get();
        return super.estimate() + model.primitive2() * 2;
      }

      @Override
      protected void update(Object p, PrimitiveObjectInspector inputOI) {
        // DateWritable is mutable, DateStatsAgg needs its own copy
        DateWritable v = new DateWritable((DateWritable) inputOI.getPrimitiveWritableObject(p));

        //Update min counter if new value is less than min seen so far
        if (min == null || v.compareTo(min) < 0) {
          min = v;
        }
        //Update max counter if new value is greater than max seen so far
        if (max == null || v.compareTo(max) > 0) {
          max = v;
        }
        // Add value to NumDistinctValue Estimator
        numDV.addToEstimator(v.getDays());
      }

      @Override
      protected void updateMin(Object minValue, DateObjectInspector minFieldOI) {
        if ((minValue != null) && (min == null ||
            min.compareTo(minFieldOI.getPrimitiveWritableObject(minValue)) > 0)) {
          // DateWritable is mutable, DateStatsAgg needs its own copy
          min = new DateWritable(minFieldOI.getPrimitiveWritableObject(minValue));
        }
      }

      @Override
      protected void updateMax(Object maxValue, DateObjectInspector maxFieldOI) {
        if ((maxValue != null) && (max == null ||
            max.compareTo(maxFieldOI.getPrimitiveWritableObject(maxValue)) < 0)) {
          // DateWritable is mutable, DateStatsAgg needs its own copy
          max = new DateWritable(maxFieldOI.getPrimitiveWritableObject(maxValue));
        }
      }
    };

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      AggregationBuffer result = new DateStatsAgg();
      reset(result);
      return result;
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      ((NumericStatsAgg)agg).reset("Date");
    }
  }
}
