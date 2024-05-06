package bacmman.data_structure;

import bacmman.data_structure.dao.MasterDAO;
import bacmman.utils.JSONUtils;
import io.objectbox.annotation.*;
import org.json.simple.parser.ParseException;

@Entity
@Uid(1000)
public class SelectionBox {
    @Id
    @Uid(1001)
    long id;
    @Index
    @Uid(1002)
    String name;
    @Index
    @Uid(1003)
    int objectClassIdx;
    @Uid(1004)
    String jsonContent;
    @Transient
    Selection selection;
    public SelectionBox() {}
    public SelectionBox(long id, String name, int objectCLassIdx, String jsonContent) {
        this.id=id;
        this.name = name;
        this.objectClassIdx = objectCLassIdx;
        this.jsonContent = jsonContent;
    }

    public SelectionBox(Selection selection) {
        this.id=0;
        this.name = selection.getName();
        this.objectClassIdx = selection.getStructureIdx();
        this.selection = selection;
        setJsonContent();
    }

    public SelectionBox updateSelection(Selection selection) {
        this.selection = selection;
        this.name = selection.getName();
        this.objectClassIdx = selection.getStructureIdx();
        setJsonContent();
        return this;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Selection getSelection(MasterDAO mDAO) {
        if (selection == null) {
            synchronized (this) {
                if (selection == null) {
                    if (jsonContent == null) selection = new Selection(name, mDAO);
                    else {
                        try {
                            selection = JSONUtils.parse(Selection.class, jsonContent);
                            selection.setMasterDAO(mDAO);
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }

                    }
                }
            }
        }
        return selection;
    }

    public void setJsonContent() {
        if (selection == null) jsonContent = null;
        else jsonContent = selection.toJSONEntry().toJSONString();
    }
    public void setJsonContent(String content) {
        this.jsonContent = content;
    }
}
