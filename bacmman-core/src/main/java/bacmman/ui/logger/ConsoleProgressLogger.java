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

/**
 *
 * @author Jean Ollion
 */
public class ConsoleProgressLogger implements ProgressLogger {
    
    @Override
    public void setProgress(int i) {
        setMessage("Progress: "+i+"%");
    }

    @Override
    public void setMessage(String message) {
        System.out.println(">"+message);
    }

    @Override
    public void setRunning(boolean running) {
        
    }

    @Override
    public boolean isGUI() {
        return false;
    }

}
