package bacmman.configuration.parameters;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Conjunction of validation functions, without duplicates.
 * Validation functions are transferred between parameters at each duplicate / setContentFrom / setPlugin call: chaining them with {@link Predicate#and} made the chain grow at each transfer (e.g. a parameter synchronized repeatedly with a copy of itself), until testing it caused a StackOverflowError.
 * Here, adding a function that is already present has no effect, adding another {@link ValidationFunctions} adds only its missing functions, and functions are tested iteratively.
 */
public class ValidationFunctions<T> implements Predicate<T> {
    private final List<Predicate<? super T>> functions = new CopyOnWriteArrayList<>(); // functions are added at configuration time, tested e.g. by the GUI

    /**
     * @param function validation function or {@link ValidationFunctions} (its functions are added); ignored if null or already present
     * @return this instance
     */
    public ValidationFunctions<T> add(Predicate<? super T> function) {
        if (function == null || function == this) return this;
        if (function instanceof ValidationFunctions) {
            for (Predicate<?> f : ((ValidationFunctions<?>) function).functions) addIfAbsent((Predicate<? super T>)f);
        } else addIfAbsent(function);
        return this;
    }

    private void addIfAbsent(Predicate<? super T> function) {
        synchronized (functions) {
            for (Predicate<? super T> f : functions) if (f == function) return; // identity: lambdas do not implement equals
            functions.add(function);
        }
    }

    public int size() {
        return functions.size();
    }

    @Override
    public boolean test(T t) {
        for (Predicate<? super T> f : functions) if (!f.test(t)) return false;
        return true;
    }
}
