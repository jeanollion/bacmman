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
package bacmman.configuration.parameters;

import java.util.function.Consumer;

/**
 * Uses consumer (and not runnable) in order to be able to transfer a listener to a duplicated object
 * the listener should be relative to the source parameter
 * @author Jean Ollion
 */
public interface Listenable<P extends Parameter> {
    public void addListener(Consumer<P> listener);
    public void removeListener(Consumer<P> listener);
    public void fireListeners();
}
