/*******************************************************************************
 * Copyright (c) 2010-2019 Haifeng Li
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/

package smile.classification;

import java.util.Arrays;
import smile.math.MathEx;
import smile.math.matrix.Matrix;
import smile.math.matrix.DenseMatrix;
import smile.math.matrix.EVD;
import smile.projection.Projection;

/**
 * Fisher's linear discriminant. Fisher defined the separation between two
 * distributions to be the ratio of the variance between the classes to
 * the variance within the classes, which is, in some sense, a measure
 * of the signal-to-noise ratio for the class labeling. FLD finds a linear
 * combination of features which maximizes the separation after the projection.
 * The resulting combination may be used for dimensionality reduction
 * before later classification.
 * <p>
 * The terms Fisher's linear discriminant and LDA are often used
 * interchangeably, although FLD actually describes a slightly different
 * discriminant, which does not make some of the assumptions of LDA such
 * as normally distributed classes or equal class covariances.
 * When the assumptions of LDA are satisfied, FLD is equivalent to LDA.
 * <p>
 * FLD is also closely related to principal component analysis (PCA), which also
 * looks for linear combinations of variables which best explain the data.
 * As a supervised method, FLD explicitly attempts to model the
 * difference between the classes of data. On the other hand, PCA is a
 * unsupervised method and does not take into account any difference in class.
 * <p>
 * One complication in applying FLD (and LDA) to real data
 * occurs when the number of variables/features does not exceed
 * the number of samples. In this case, the covariance estimates do not have
 * full rank, and so cannot be inverted. This is known as small sample size
 * problem.
 *
 * <h2>References</h2>
 * <ol>
 * <li> Robust and Accurate Cancer Classification with Gene Expression Profiling http://alumni.cs.ucr.edu/~hli/paper/hli05tumor.pdf.</li>
 * </ol>
 *
 * @see LDA
 * @see smile.projection.PCA
 * 
 * @author Haifeng Li
 */
public class FLD implements Classifier<double[]>, Projection<double[]> {
    private static final long serialVersionUID = 1L;

    /**
     * The dimensionality of data.
     */
    private final int p;
    /**
     * The number of classes.
     */
    private final int k;
    /**
     * Original common mean vector.
     */
    private final double[] mean;
    /**
     * Original class mean vectors.
     */
    private final DenseMatrix mu;
    /**
     * Project matrix.
     */
    private final DenseMatrix scaling;
    /**
     * Projected common mean vector.
     */
    private final double[] smean;
    /**
     * Projected class mean vectors.
     */
    private final double[][] smu;
    
    /**
     * Constructor. Learn Fisher's linear discriminant.
     * @param x training instances.
     * @param y training labels in [0, k), where k is the number of classes.
     */
    public FLD(double[][] x, int[] y) {
        this(x, y, -1);
    }

    /**
     * Constructor. Learn Fisher's linear discriminant.
     * @param x training instances.
     * @param y training labels in [0, k), where k is the number of classes.
     * @param L the dimensionality of mapped space.
     */
    public FLD(double[][] x, int[] y, int L) {
        this(x, y, L, 1E-4);
    }

    /**
     * Constructor. Learn Fisher's linear discriminant.
     * @param x training instances.
     * @param y training labels in [0, k), where k is the number of classes.
     * @param L the dimensionality of mapped space.
     * @param tol a tolerance to decide if a covariance matrix is singular; it
     * will reject variables whose variance is less than tol<sup>2</sup>.
     */
    public FLD(double[][] x, int[] y, int L, double tol) {
        if (x.length != y.length) {
            throw new IllegalArgumentException(String.format("The sizes of X and Y don't match: %d != %d", x.length, y.length));
        }

        // class label set.
        int[] labels = MathEx.unique(y);
        Arrays.sort(labels);
        
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] < 0) {
                throw new IllegalArgumentException("Negative class label: " + labels[i]); 
            }
            
            if (i > 0 && labels[i] - labels[i-1] > 1) {
                throw new IllegalArgumentException("Missing class: " + (labels[i-1]+1));
            }
        }

        k = labels.length;
        if (k < 2) {
            throw new IllegalArgumentException("Only one class.");            
        }
        
        if (tol < 0.0) {
            throw new IllegalArgumentException("Invalid tol: " + tol);
        }
        
        if (x.length <= k) {
            throw new IllegalArgumentException(String.format("Sample size is too small: %d <= %d", x.length, k));
        }

        if (L >= k) {
            throw new IllegalArgumentException(String.format("The dimensionality of mapped space is too high: %d >= %d", L, k));
        }

        if (L <= 0) {
            L = k - 1;
        }

        final int n = x.length;
        p = x[0].length;

        // The number of instances in each class.
        int[] ni = new int[k];
        // Common mean vector.
        mean = MathEx.colMeans(x);
        // Common covariance.
        DenseMatrix T = Matrix.zeros(p, p);
        // Class mean vectors.
        mu = Matrix.zeros(k, p);

        for (int i = 0; i < n; i++) {
            int c = y[i];
            ni[c]++;
            for (int j = 0; j < p; j++) {
                mu.add(c, j, x[i][j]);
            }
        }

        for (int i = 0; i < k; i++) {
            for (int j = 0; j < p; j++) {
                mu.div(i, j, ni[i]);
                mu.sub(i, j, mean[j]);
            }
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < p; j++) {
                for (int l = 0; l <= j; l++) {
                    T.add(j, l, (x[i][j] - mean[j]) * (x[i][l] - mean[l]));
                }
            }
        }

        for (int j = 0; j < p; j++) {
            for (int l = 0; l <= j; l++) {
                T.div(j, l, n);
                T.set(l, j, T.get(j, l));
            }
        }

        // Between class scatter
        DenseMatrix B = Matrix.zeros(p, p);
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < p; j++) {
                for (int l = 0; l <= j; l++) {
                    B.add(j, l, mu.get(i, j) * mu.get(i, l));
                }
            }
        }

        for (int j = 0; j < p; j++) {
            for (int l = 0; l <= j; l++) {
                B.div(j, l, k);
                B.set(l, j, B.get(j, l));
            }
        }

        T.setSymmetric(true);
        EVD eigen = T.eigen();
        
        tol = tol * tol;
        double[] s = eigen.getEigenValues();
        for (int i = 0; i < s.length; i++) {
            if (s[i] < tol) {
                throw new IllegalArgumentException("The covariance matrix is close to singular.");
            }

            s[i] = 1.0 / s[i];
        }

        DenseMatrix U = eigen.getEigenVectors();
        DenseMatrix UB = U.atbmm(B);

        for (int i = 0; i < k; i++) {
            for (int j = 0; j < p; j++) {
                UB.mul(i, j, s[j]);
            }
        }

        B = U.abmm(UB);
        B.setSymmetric(true);
        eigen = B.eigen();

        U = eigen.getEigenVectors();
        scaling = Matrix.zeros(p, L);
        for (int j = 0; j < L; j++) {
            for (int i = 0; i < p; i++) {
                scaling.set(i, j, U.get(i, j));
            }
        }
        
        smean = new double[L];
        scaling.atx(mean, smean);
        smu = mu.abmm(scaling).toArray();
    }

    @Override
    public int predict(double[] x) {
        if (x.length != p) {
            throw new IllegalArgumentException(String.format("Invalid input vector size: %d, expected: %d", x.length, p));
        }

        double[] wx = project(x);

        int y = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double d = MathEx.distance(wx, smu[i]);
            if (d < nearest) {
                nearest = d;
                y = i;
            }
        }

        return y;
    }

    @Override
    public double[] project(double[] x) {
        if (x.length != p) {
            throw new IllegalArgumentException(String.format("Invalid input vector size: %d, expected: %d", x.length, p));
        }

        double[] y = new double[scaling.ncols()];
        scaling.atx(x, y);
        MathEx.sub(y, smean);
        return y;
    }

    @Override
    public double[][] project(double[][] x) {
        double[][] y = new double[x.length][scaling.ncols()];
        
        for (int i = 0; i < x.length; i++) {
            if (x[i].length != p) {
                throw new IllegalArgumentException(String.format("Invalid input vector size: %d, expected: %d", x[i].length, p));
            }

            scaling.atx(x[i], y[i]);
            MathEx.sub(y[i], smean);
        }
        
        return y;
    }

    /**
     * Returns the projection matrix W. The dimension reduced data can be obtained
     * by y = W' * x.
     */
    public DenseMatrix getProjection() {
        return scaling;
    }
}
