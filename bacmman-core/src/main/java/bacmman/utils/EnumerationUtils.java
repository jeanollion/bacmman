package bacmman.utils;

import java.util.Enumeration;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class EnumerationUtils {
    public static <T> Stream<T> toStream(Enumeration<T> enumeration) {
        EnumerationSpliterator<T> spliterator = new EnumerationSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED, enumeration);
        return StreamSupport.stream(spliterator, false);
    }

    public static class EnumerationSpliterator<T> extends Spliterators.AbstractSpliterator<T> {

        private final Enumeration<T> enumeration;

        public EnumerationSpliterator(long est, int additionalCharacteristics, Enumeration<T> enumeration) {
            super(est, additionalCharacteristics);
            this.enumeration = enumeration;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (enumeration.hasMoreElements()) {
                action.accept(enumeration.nextElement());
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            while (enumeration.hasMoreElements())
                action.accept(enumeration.nextElement());
        }
    }
}
