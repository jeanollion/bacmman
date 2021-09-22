package bacmman.ui;

import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class test {
    public static final Logger logger = LoggerFactory.getLogger(test.class);

    public static void main(String[] args) {
        String[] relPath = new String[] {"bla/bli/loulou", "loulou", "/loulou", "lili"};
        for (String r : relPath) logger.debug("split: {} -> {}", r, Utils.splitNameAndRelpath(r) );
        String path = "/data/Images/TestRelPath/";

        //for (String r : relPath) logger.debug("search: {} -> {}", r, Utils.search(path, r, 2));

        for (String r: relPath) logger.debug("rel path: {} -> {}", r, Utils.convertRelPathToFilename(path, r));
    }
}
