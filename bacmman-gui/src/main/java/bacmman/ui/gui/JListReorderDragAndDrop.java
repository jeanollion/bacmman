package bacmman.ui.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.datatransfer.*;
import java.io.IOException;

public class JListReorderDragAndDrop {
    static final Logger logger = LoggerFactory.getLogger(JListReorderDragAndDrop.class);
    public static <T> void enableDragAndDrop(JList<T> list, DefaultListModel<T> model, Class<T> clazz) {
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setTransferHandler(new TransferHandler() {
            private int index;
            private boolean beforeIndex = false; //Start with `false` therefore if it is removed from or added to the list it still works

            @Override
            public int getSourceActions(JComponent comp) {
                return MOVE;
            }

            @Override
            public Transferable createTransferable(JComponent comp) {
                index = list.getSelectedIndex();
                return new SimpleTransferable<T>(list.getSelectedValue());
            }

            @Override
            public void exportDone(JComponent comp, Transferable trans, int action) {
                if (action == MOVE) {
                    if(beforeIndex)
                        model.remove(index + 1);
                    else
                        model.remove(index);
                }
            }

            @Override
            public boolean canImport(TransferHandler.TransferSupport support) {
                try {
                    Object data = support.getTransferable().getTransferData(new DataFlavor(clazz, "type data flavor"));
                    return data!=null && data.getClass()==clazz;
                } catch (UnsupportedFlavorException | IOException e) {
                    logger.debug("cannot dnd" ,e);
                    return false;
                }
            }

            @Override
            public boolean importData(TransferHandler.TransferSupport support) {
                try {
                    T data = (T)support.getTransferable().getTransferData(new DataFlavor(clazz, "type data flavor"));
                    JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                    model.add(dl.getIndex(), data);
                    beforeIndex = dl.getIndex() < index ? true : false;
                    return true;
                } catch (UnsupportedFlavorException | IOException e) {
                    logger.debug("dnd error", e);
                }

                return false;
            }
        });
    }
    public static class SimpleTransferable<T> implements Transferable, ClipboardOwner {
        T data;
        public SimpleTransferable(T data) {
            this.data= data;
        }
        public DataFlavor[] getTransferDataFlavors() {
            // returning flavors itself would allow client code to modify
            // our internal behavior
            return new DataFlavor[]{new DataFlavor(data.getClass(), "type data flavor")};
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return true;
        }

        public T getTransferData(DataFlavor flavor) {
            return data;
        }

        public void lostOwnership(Clipboard clipboard, Transferable contents) {
        }
    }
}
