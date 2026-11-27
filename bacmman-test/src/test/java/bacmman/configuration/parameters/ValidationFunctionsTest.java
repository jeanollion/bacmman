package bacmman.configuration.parameters;

import org.junit.Test;

import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validation functions are transferred at each duplicate / setContentFrom: they must not accumulate.
 * They used to be chained with Predicate.and, and a parameter synchronized repeatedly with a copy of itself ended with a StackOverflowError when validated.
 */
public class ValidationFunctionsTest {
    static final int REPEATS = 20000; // the chained version overflowed after ~10000 transfers

    @Test
    public void noDuplicates() {
        Predicate<Integer> positive = i -> i > 0;
        ValidationFunctions<Integer> v = new ValidationFunctions<>();
        v.add(positive).add(positive).add(null);
        ValidationFunctions<Integer> other = new ValidationFunctions<Integer>().add(positive).add(i -> i < 10);
        for (int i = 0; i < REPEATS; ++i) {
            v.add(other);
            other.add(v);
            v.add(v);
        }
        assertEquals(2, v.size());
        assertEquals(2, other.size());
        assertTrue(v.test(5));
        assertFalse(v.test(0));
        assertFalse(v.test(10));
    }

    @Test
    public void repeatedSetContentFromList() {
        SimpleListParameter<BoundedNumberParameter> l1 = new SimpleListParameter<>("list", new BoundedNumberParameter("n", 0, 1, 0, 10));
        l1.addValidationFunction(l -> l.getChildCount() <= 2);
        l1.addValidationFunctionToChildren(n -> n.getIntValue() != 5);
        SimpleListParameter<BoundedNumberParameter> l2 = l1.duplicate();
        for (int i = 0; i < REPEATS; ++i) {
            l2.setContentFrom(l1);
            l1.setContentFrom(l2);
        }
        assertEquals("list validation functions", 1, l1.additionalValidation.size());
        assertEquals("children validation functions", 1, l1.childrenValidation.size());
        assertTrue(l1.isValid()); // threw StackOverflowError
        l1.insert(l1.createChildInstance());
        l1.getChildAt(0).setValue(5);
        assertFalse("children validation still applies", l1.isValid());
        l1.getChildAt(0).setValue(1);
        assertTrue(l1.isValid());
        l1.insert(l1.createChildInstance(), l1.createChildInstance());
        assertFalse("list validation still applies", l1.isValid());
    }

    @Test
    public void repeatedDuplicate() {
        BoundedNumberParameter p = new BoundedNumberParameter("n", 0, 1, 0, 10).addValidationFunction(n -> n.getIntValue() != 5);
        for (int i = 0; i < REPEATS; ++i) p = p.duplicate();
        assertEquals(1, p.additionalValidation.size());
        assertTrue(p.isValid());
        p.setValue(5);
        assertFalse(p.isValid());
    }
}
