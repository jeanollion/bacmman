package bacmman.utils;

import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface TriFunction<T, U, V, R> {
    R apply(T var1, U var2, V var3);

    default <S> TriFunction<T, U, V, S> andThen(Function<? super R, ? extends S> after) {
        Objects.requireNonNull(after);
        return (t, u, v) -> {
            return after.apply(this.apply(t, u, v));
        };
    }
}