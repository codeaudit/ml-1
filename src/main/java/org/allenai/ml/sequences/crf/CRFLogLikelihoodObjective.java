package org.allenai.ml.sequences.crf;

import org.allenai.ml.linalg.Vector;
import org.allenai.ml.objective.ExampleObjectiveFn;
import org.allenai.ml.sequences.ForwardBackwards;
import org.allenai.ml.sequences.Transition;
import com.gs.collections.api.block.function.primitive.IntToIntFunction;
import lombok.RequiredArgsConstructor;
import lombok.val;

import java.util.List;

/**
 * Log-likelihood objective function for CRF training. The objective per-example boils down to
 * an actual and expected components:
 * - __actual__: The score is the sum of the transitions involved with the gold labels (the log potentials).
 *               The gradient contribution is the sum of predicates involved with those transitions
 * @param <S>
 */
@RequiredArgsConstructor
public class CRFLogLikelihoodObjective<S> implements ExampleObjectiveFn<CRFIndexedExample> {

    private final CRFWeightsEncoder<S> weightEncoder;

    @Override
    public double evaluate(CRFIndexedExample example, Vector inParams, Vector outGrad) {
        if (!example.isLabeled()) {
            throw new IllegalArgumentException("Requires labeled example");
        }
        double[][] logPotentials = weightEncoder.fillPotentials(inParams, example);
        val fb = new ForwardBackwards<S>(weightEncoder.stateSpace);
        val fbResult = fb.compute(logPotentials);
        // Actual
        int[] goldLabels = example.getGoldLabels();
        double logNumerator = 0.0;
        for (int idx = 0; idx + 1 < goldLabels.length; idx++) {
            int from = goldLabels[idx];
            int to = goldLabels[idx + 1];
            val transition = weightEncoder.stateSpace.transitionFor(from, to);
            if (!transition.isPresent()) {
                val states = weightEncoder.stateSpace.states();
                throw new IllegalArgumentException(String.format("Gold transition doesn't exist [%s, %s]",
                    states.get(from), states.get(to)));
            }
            // Objective contribution is the sum of gold transition scores
            logNumerator += logPotentials[idx][transition.get().selfIndex];
            // Gradient are the features on those transitions
            Vector.Iterator nodePredIt = example.getNodePredicateValues(idx);
            updateGrad(outGrad, nodePredIt, (predIdx) -> weightEncoder.nodeWeightIndex(predIdx, from), 1.0);
            Vector.Iterator edgePredIt = example.getEdgePredicateValues(idx);
            int transIdx = transition.get().selfIndex;
            updateGrad(outGrad, edgePredIt, (predIdx) -> weightEncoder.edgeWeightIndex(predIdx, transIdx), 1.0);
        }
        // Expected
        double logDenominator = fbResult.getLogZ();
        double[][] nodeMarginals = fbResult.getNodeMarginals();
        double[][] edgeMarginals = fbResult.getEdgeMarginals();
        int numStates = weightEncoder.stateSpace.states().size();
        int numTransitions = weightEncoder.stateSpace.transitions().size();
        for (int idx = 0; idx+1 < example.getSequenceLength(); idx++) {
            Vector.Iterator nodePreds = example.getNodePredicateValues(idx);
            while (!nodePreds.isExhausted()) {
                int predIdx = (int) nodePreds.index();
                double predVal = nodePreds.value();
                for (int s = 0; s < numStates; s++) {
                    int weightIdx = weightEncoder.nodeWeightIndex(predIdx, s);
                    outGrad.inc(weightIdx, predVal * -nodeMarginals[idx][s]);
                }
                nodePreds.advance();
            }
            Vector.Iterator edgePreds = example.getEdgePredicateValues(idx);
            while (!edgePreds.isExhausted()) {
                int predIdx = (int) edgePreds.index();
                double predVal = edgePreds.value();
                for (int t=0; t < numTransitions; ++t) {
                    int weightIdx = weightEncoder.edgeWeightIndex(predIdx, t);
                    outGrad.inc(weightIdx, predVal * -edgeMarginals[idx][t]);
                }
                edgePreds.advance();
            }
        }
        assert logNumerator <= logDenominator;
        return logNumerator - logDenominator;
    }

    private void updateGrad(Vector outGrad, Vector.Iterator predIt, IntToIntFunction weightIndexMap, double scale) {
        predIt.reset();
        while (!predIt.isExhausted()) {
            int predIdx = (int) predIt.index();
            double predVal = predIt.value();
            int weightIdx = weightIndexMap.valueOf(predIdx);
            outGrad.inc(weightIdx, scale * predVal);
            predIt.advance();
        }
    }
}
