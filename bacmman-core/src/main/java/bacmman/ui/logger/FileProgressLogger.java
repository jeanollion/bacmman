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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import bacmman.utils.FileIO;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class FileProgressLogger implements ProgressLogger {
    File logFile;
    FileLock xpFileLock;
    RandomAccessFile logFileWriter;
    boolean append;
    boolean logProgress;
    public FileProgressLogger(boolean append) {
        this.append = append;
    }
    public FileProgressLogger setAppend(boolean append) {
        this.append=append;
        return this;
    }
    public FileProgressLogger setLogProgress(boolean logProgress) {
        this.logProgress=logProgress;
        return this;
    }
    private synchronized void lockLogFile() {
        if (xpFileLock!=null) return;
        try {
            setMessage("locking file: "+logFile.getAbsolutePath());
            if (!logFile.exists()) logFile.createNewFile();
            logFileWriter = new RandomAccessFile(logFile, "rw");
            xpFileLock = logFileWriter.getChannel().tryLock();
        } catch (FileNotFoundException ex) {
            setMessage("no config file found!");
            logFile=null;
        } catch (OverlappingFileLockException e) {
            setMessage("file already locked");
            logFile=null;
        } catch (IOException ex) {
            setMessage("File could not be locked");
            logFile=null;
        }
    }
    public synchronized void unlockLogFile() {
        if (this.xpFileLock!=null) {
            try {
                //setMessage("realising lock: "+ xpFileLock);
                xpFileLock.release();
            } catch (IOException ex) {
                setMessage("error realeasing dataset lock");
            } finally {
                xpFileLock = null;
            }
        }
        if (logFileWriter!=null) {
            try {
                logFileWriter.close();
            } catch (IOException ex) {
                setMessage("could not close config file");
            } finally {
                logFileWriter = null;
            }
        }
    }
    public File getLogFile() {
        return logFile;
    }
    public void setLogFile(String dir) {
        if (dir==null) {
            this.logFile=null;
            this.unlockLogFile();
            return;
        }
        if (logFileWriter!=null) this.unlockLogFile();
        logFile = new File(dir);
        if (!append && logFile.exists()) {
            lockLogFile();
            if (this.logFileWriter!=null) {
                try {
                    FileIO.clearRAF(logFileWriter);
                } catch (IOException ex) { }
            }
            unlockLogFile();
        }
    }
    
    @Override
    public void setProgress(int i) {
        if (logProgress) setMessage("Progress: "+i+"%");
    }

    @Override
    public void setMessage(String message) {
        if (logFileWriter!=null) {
            try {
                FileIO.write(logFileWriter, Utils.getFormattedTime()+": "+message, true);
            } catch (IOException ex) {
                System.out.println(">cannot log to file:"+ (logFile==null ? "null" : logFile.getAbsolutePath()));
            }
        }
    }

    @Override
    public void setRunning(boolean running) {
        if (running) this.lockLogFile();
        else this.unlockLogFile();
    }
    
}
