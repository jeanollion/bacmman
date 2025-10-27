package bacmman.data_structure.dao;

import java.nio.file.Path;

public interface PersistentMasterDAO<ID, T extends ObjectDAO<ID>> extends MasterDAO<ID, T> {
    boolean containsDatabase(Path outputPath);
    void compact();
}
