package bacmman.data_structure.dao;

import bacmman.data_structure.MasterDAOFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

public class DuplicateMasterDAO<sourceID, ID> extends MemoryMasterDAO<ID, DuplicateObjectDAO<sourceID, ID>> {
    public DuplicateMasterDAO(MasterDAO<sourceID, ?> sourceMasterDAO, Function<Integer, ID> idGenerator, Collection<Integer> excludeOC) {
        super(sourceMasterDAO.getAccess(), (mDAO, positionName) -> new DuplicateObjectDAO<>(mDAO, sourceMasterDAO.getDao(positionName), idGenerator, excludeOC));
        this.xp=sourceMasterDAO.getExperiment().duplicate();
        this.datasetDir = sourceMasterDAO.getDatasetDir();
        MasterDAO.configureExperiment(this, xp);
    }

}
