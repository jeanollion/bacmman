package bacmman.processing.clustering;

import bacmman.measurement.BasicMeasurements;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;

import java.util.function.Predicate;

public interface FusionCriterion<E, T extends Interface<E, T>> {
    boolean checkFusion(T inter);
    void elementChanged(E e);

    class SimpleFusionCriterion<E, T extends Interface<E, T>> implements FusionCriterion<E, T> {
        final Predicate<T> forbidFusion;
        public SimpleFusionCriterion(Predicate<T> forbidFusion) {
            this.forbidFusion = forbidFusion;
        }

        @Override
        public boolean checkFusion(T inter) {
            return !forbidFusion.test(inter);
        }

        @Override
        public void elementChanged(E e) { }
    }
    interface AcceptsFusionCriterion<E, T extends Interface<E, T>> {
        void addFusionCriterion(FusionCriterion<E, T> crit);
    }
}
