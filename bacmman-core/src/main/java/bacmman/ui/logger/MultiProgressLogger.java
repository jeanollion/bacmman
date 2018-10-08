/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.ui.logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class MultiProgressLogger  implements ProgressLogger {
    List<ProgressLogger> uis = new ArrayList<>();
    public MultiProgressLogger(ProgressLogger... uis) {
        this.uis.addAll(Arrays.asList(uis));
    }
    public MultiProgressLogger addUIs(ProgressLogger... uis) {
        this.uis.addAll(Arrays.asList(uis));
        return this;
    }
    @Override
    public void setProgress(int i) {
        uis.stream().forEach((ui) -> {ui.setProgress(i);});
    }

    @Override
    public void setMessage(String message) {
        uis.stream().forEach((ui) -> {ui.setMessage(message);});
    }

    @Override
    public void setRunning(boolean running) {
        uis.stream().forEach((ui) -> {ui.setRunning(running);});
    }
    public void applyToLogUserInterfaces(Consumer<FileProgressLogger> function) {
        uis.stream().filter((ui) -> (ui instanceof FileProgressLogger)).forEach((ui) -> function.accept((FileProgressLogger)ui));
    }
}
