package bacmman.plugins;

public interface TestableOperation {
    enum TEST_MODE {NO_TEST(false, false),
        TEST_EXPERT(true, true),
        TEST_SIMPLE(true, false);
        private final boolean simple, expert;
        TEST_MODE(boolean simple, boolean expert) {
            this.simple=simple;
            this.expert=expert;
        }
        public boolean testSimple() {return simple;}
        public boolean testExpert() {return expert;}
    }
    void setTestMode(TEST_MODE testMode);
}
