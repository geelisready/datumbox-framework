/**
 * Copyright (C) 2013-2016 Vasilis Vryniotis <bbriniotis@datumbox.com>
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
package com.datumbox.framework.machinelearning.regression;

import com.datumbox.framework.machinelearning.common.interfaces.StepwiseCompatible;
import com.datumbox.framework.machinelearning.common.bases.basemodels.BaseLinearRegression;
import com.datumbox.common.dataobjects.Dataframe;
import com.datumbox.common.dataobjects.MatrixDataframe;
import com.datumbox.common.dataobjects.Record;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConnector;
import com.datumbox.common.persistentstorage.interfaces.BigMap;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConfiguration;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConnector.MapType;
import com.datumbox.common.persistentstorage.interfaces.DatabaseConnector.StorageHint;
import com.datumbox.common.utilities.PHPfunctions;
import com.datumbox.framework.statistics.distributions.ContinuousDistributions;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;


/**
 * Performs Linear Regression using Matrices.
 * 
 * WARNING: This class copies the Dataframe to a RealMatrix which forces all of the
 * data to be loaded in memory.
 * 
 * @author Vasilis Vryniotis <bbriniotis@datumbox.com>
 */
public class MatrixLinearRegression extends BaseLinearRegression<MatrixLinearRegression.ModelParameters, MatrixLinearRegression.TrainingParameters, MatrixLinearRegression.ValidationMetrics> implements StepwiseCompatible {

    /** {@inheritDoc} */
    public static class ModelParameters extends BaseLinearRegression.ModelParameters {
        private static final long serialVersionUID = 1L;

        /**
         * Feature set
         */
        @BigMap(mapType=MapType.HASHMAP, storageHint=StorageHint.IN_MEMORY)
        private Map<Object, Integer> featureIds; //list of all the supported features
        
        private Map<Object, Double> featurePvalues; //array with all the pvalues of the features
    
        /** 
         * @see com.datumbox.framework.machinelearning.common.bases.baseobjects.BaseModelParameters#BaseModelParameters(com.datumbox.common.persistentstorage.interfaces.DatabaseConnector) 
         */
        protected ModelParameters(DatabaseConnector dbc) {
            super(dbc);
        }
        
        /**
         * Getter for the mapping of the column names to column ids. The implementation
         * internally converts the data into vectors and as a result we need to 
         * estimate and store the mapping between the column names and their 
         * positions in the array. This mapping is estimated during training.
         * 
         * @return 
         */
        public Map<Object, Integer> getFeatureIds() {
            return featureIds;
        }
        
        /**
         * Setter for the mapping of the column names to column ids.
         * 
         * @param featureIds 
         */
        protected void setFeatureIds(Map<Object, Integer> featureIds) {
            this.featureIds = featureIds;
        }
        
        /**
         * Getter for the p-values of the variables which are estimated during 
         * the regression. 
         * This is NOT always available. Calculated during training ONLY if the model is
         * configured to. It is useful when we perform StepwiseRegression.
         * 
         * @return 
         */
        public Map<Object, Double> getFeaturePvalues() {
            return featurePvalues;
        } 
        
        /**
         * Setter for the p-values of the variables which are estimated during 
         * the regression.
         * 
         * @param featurePvalues 
         */
        protected void setFeaturePvalues(Map<Object, Double> featurePvalues) {
            this.featurePvalues = featurePvalues;
        } 
    } 

    /** {@inheritDoc} */
    public static class TrainingParameters extends BaseLinearRegression.TrainingParameters {
        private static final long serialVersionUID = 1L;

    } 
    
    /** {@inheritDoc} */
    public static class ValidationMetrics extends BaseLinearRegression.ValidationMetrics {
        private static final long serialVersionUID = 1L;
        
    }

    /**
     * Public constructor of the algorithm.
     * 
     * @param dbName
     * @param dbConf 
     */
    public MatrixLinearRegression(String dbName, DatabaseConfiguration dbConf) {
        super(dbName, dbConf, MatrixLinearRegression.ModelParameters.class, MatrixLinearRegression.TrainingParameters.class, MatrixLinearRegression.ValidationMetrics.class);
    }

    /** {@inheritDoc} */
    @Override
    public Map<Object, Double> getFeaturePvalues() {
        return knowledgeBase.getModelParameters().getFeaturePvalues();
    }
    
    @Override
    protected void _fit(Dataframe trainingData) {
        ModelParameters modelParameters = knowledgeBase.getModelParameters();
        int n = modelParameters.getN();
        int d = modelParameters.getD();
        
        Map<Object, Double> thitas = modelParameters.getThitas();
        Map<Object, Integer> featureIds = modelParameters.getFeatureIds();
        Map<Integer, Integer> recordIdsReference = null; //this reference is not needed
        MatrixDataframe matrixDataset = MatrixDataframe.newInstance(trainingData, true, recordIdsReference, featureIds);
        
        RealVector Y = matrixDataset.getY();
        RealMatrix X = matrixDataset.getX();
        
        //(X'X)^-1
        RealMatrix Xt = X.transpose();
        LUDecomposition lud = new LUDecomposition(Xt.multiply(X));
        RealMatrix XtXinv = lud.getSolver().getInverse();
        lud =null;
        
        //(X'X)^-1 * X'Y
        RealVector coefficients = XtXinv.multiply(Xt).operate(Y);
        Xt = null;
        
        //put the features coefficients in the thita map
        thitas.put(Dataframe.constantColumnName, coefficients.getEntry(0));
        for(Map.Entry<Object, Integer> entry : featureIds.entrySet()) {
            Object feature = entry.getKey();
            Integer featureId = entry.getValue();
            
            thitas.put(feature, coefficients.getEntry(featureId));
        }
        
        
        //get the predictions and subtact the Y vector. Sum the squared differences to get the error
        double SSE = 0.0;
        for(double v : X.operate(coefficients).subtract(Y).toArray()) {
            SSE += v*v;
        }
        Y = null;

        //standard error matrix
        double MSE = SSE/(n-(d+1)); //mean square error = SSE / dfResidual
        RealMatrix SE = XtXinv.scalarMultiply(MSE);
        XtXinv = null;

        //creating a flipped map of ids to features
        Map<Integer, Object> idsFeatures = PHPfunctions.array_flip(featureIds);


        Map<Object, Double> pvalues = new HashMap<>(); //This is not small, but it does not make sense to store it in the db
        for(int i =0;i<(d+1);++i) {
            double error = SE.getEntry(i, i);
            Object feature = idsFeatures.get(i);
            if(error<=0.0) {
                //double tstat = Double.MAX_VALUE;
                pvalues.put(feature, 0.0);
            }
            else {
                double tstat = coefficients.getEntry(i)/Math.sqrt(error);
                pvalues.put(feature, 1.0-ContinuousDistributions.StudentsCdf(tstat, n-(d+1))); //n-d degrees of freedom
            }
        }
        SE=null;
        coefficients=null;
        idsFeatures=null;
        matrixDataset = null;

        modelParameters.setFeaturePvalues(pvalues);

    }

    @Override
    protected void predictDataset(Dataframe newData) {
        //read model params
        ModelParameters modelParameters = knowledgeBase.getModelParameters();

        int d = modelParameters.getD()+1; //plus one for the constant
        
        Map<Object, Double> thitas = modelParameters.getThitas();
        Map<Object, Integer> featureIds = modelParameters.getFeatureIds();
        
        RealVector coefficients = new ArrayRealVector(d);
        for(Map.Entry<Object, Double> entry : thitas.entrySet()) {
            Integer featureId = featureIds.get(entry.getKey());
            coefficients.setEntry(featureId, entry.getValue());
        }
        
        Map<Integer, Integer> recordIdsReference = new HashMap<>(); //use a mapping between recordIds and rowIds in Matrix
        MatrixDataframe matrixDataset = MatrixDataframe.parseDataset(newData, recordIdsReference, featureIds);
        
        RealMatrix X = matrixDataset.getX();
        
        RealVector Y = X.operate(coefficients);
        for(Map.Entry<Integer, Record> e : newData.entries()) {
            Integer rId = e.getKey();
            Record r = e.getValue();
            int rowId = recordIdsReference.get(rId);
            newData._unsafe_set(rId, new Record(r.getX(), r.getY(), Y.getEntry(rowId), r.getYPredictedProbabilities()));
        }
        
        recordIdsReference = null;
        matrixDataset = null;
    }

    
}
