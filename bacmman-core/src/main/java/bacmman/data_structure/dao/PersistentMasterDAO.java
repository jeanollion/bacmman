package bacmman.data_structure.dao;

public interface PersistentMasterDAO<ID, T extends ObjectDAO<ID>> extends MasterDAO<ID, T> {
    boolean containsDatabase(String outputPath);
    void compact();
}
