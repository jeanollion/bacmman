package bacmman.ui.gui;

import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;

public class JListReorderDragAndDrop {
    static final Logger logger = LoggerFactory.getLogger(JListReorderDragAndDrop.class);
    public static <T> void enableDragAndDrop(JList<T> list, DefaultListModel<T> model, Class<T> clazz) {
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        list.setTransferHandler(new TransferHandler() {
            private int[] index;
            private int firstIndex;
            private boolean beforeIndex = false; //Start with `false` therefore if it is removed from or added to the list it still works
            List<T> sel;
            @Override
            public int getSourceActions(JComponent comp) {
                return MOVE;
            }

            @Override
            public Transferable createTransferable(JComponent comp) {
                index = list.getSelectedIndices();
                sel = list.getSelectedValuesList();
                firstIndex = list.getSelectedIndex();
                return new SimpleTransferable<T>(sel);
            }

            @Override
            public void exportDone(JComponent comp, Transferable trans, int action) {
                if (action == MOVE) {
                    if (beforeIndex) for (int i : index) model.remove(firstIndex + index.length );
                    else for (int i : index) model.remove(firstIndex);
                    list.setSelectedIndices(sel.stream().mapToInt(model::indexOf).toArray());
                }
            }

            @Override
            public boolean canImport(TransferHandler.TransferSupport support) {
                try {
                    if (index==null || index.length==0) return false;
                    JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                    if (IntStream.of(index).anyMatch(i->i==dl.getIndex())) return false;
                    // check continuous interval
                    for (int i = 1; i<index.length; ++i) if (index[i]!=index[i-1]+1) return false;
                    List<T> data = (List<T>)support.getTransferable().getTransferData(new DataFlavor(clazz, "type data flavor"));
                    return data!=null && !data.isEmpty() && data.get(0).getClass()==clazz;
                } catch (UnsupportedFlavorException | IOException e) {
                    logger.debug("cannot dnd" ,e);
                    return false;
                }
            }

            @Override
            public boolean importData(TransferHandler.TransferSupport support) {
                try {
                    List<T> data = (List<T>)support.getTransferable().getTransferData(new DataFlavor(clazz, "type data flavor"));
                    JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                    for (int i = 0; i<data.size(); ++i) model.add(dl.getIndex()+i, data.get(i));
                    beforeIndex = dl.getIndex() < firstIndex; // we checked that index is not in selected indices
                    return true;
                } catch (UnsupportedFlavorException | IOException e) {
                    logger.debug("dnd error", e);
                }
                return false;
            }
        });
    }
    public static class SimpleTransferable<T> implements Transferable, ClipboardOwner {
        List<T> data;
        public SimpleTransferable(List<T> data) {
            this.data= data;
        }
        public DataFlavor[] getTransferDataFlavors() {
            // returning flavors itself would allow client code to modify
            // our internal behavior
            return new DataFlavor[]{new DataFlavor(data.get(0).getClass(), "type data flavor")};
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return true;
        }

        public List<T> getTransferData(DataFlavor flavor) {
            return data;
        }

        public void lostOwnership(Clipboard clipboard, Transferable contents) {
        }
    }
}
