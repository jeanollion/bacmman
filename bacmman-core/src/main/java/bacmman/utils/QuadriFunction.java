package bacmman.utils;

import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface QuadriFunction<T, U, V, W, R> {
    R apply(T var1, U var2, V var3, W var4);

    default <S> QuadriFunction<T, U, V, W, S> andThen(Function<? super R, ? extends S> after) {
        Objects.requireNonNull(after);
        return (t, u, v, w) -> after.apply(this.apply(t, u, v, w));
    }
}