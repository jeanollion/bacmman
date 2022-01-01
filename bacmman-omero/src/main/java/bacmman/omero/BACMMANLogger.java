package bacmman.omero;

import bacmman.ui.logger.ProgressLogger;
import omero.log.LogMessage;
import omero.log.Logger;

public class BACMMANLogger implements Logger {
    private final org.slf4j.Logger logger;
    private ProgressLogger bacmmanLogger;

    public BACMMANLogger(org.slf4j.Logger logger) {
        this(logger, null);
    }
    public BACMMANLogger(org.slf4j.Logger logger, ProgressLogger bacmmanLogger) {
        this.logger=logger;
        this.bacmmanLogger=bacmmanLogger;
    }
    public BACMMANLogger setBacmmanLogger(ProgressLogger logger) {
        this.bacmmanLogger = logger;
        return this;
    }
    @Override
    public void debug(Object originator, String logMsg) {
        logger.debug("From: {} -> {}", originator, logMsg);
    }

    @Override
    public void debug(Object originator, LogMessage msg) {
        logger.debug("From: {} -> {}", originator, msg);
    }

    @Override
    public void info(Object originator, String logMsg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(logMsg);
        logger.info("From: {} -> {}", originator, logMsg);
    }

    @Override
    public void info(Object originator, LogMessage msg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(msg.toString());
        logger.info("From: {} -> {}", originator, msg);
    }

    @Override
    public void warn(Object originator, String logMsg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(logMsg);
        logger.warn("From: {} -> {}", originator, logMsg);
    }

    @Override
    public void warn(Object originator, LogMessage msg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(msg.toString());
        logger.warn("From: {} -> {}", originator, msg);
    }

    @Override
    public void error(Object originator, String logMsg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(logMsg);
        logger.error("From: {} -> {}", originator, logMsg);
    }

    @Override
    public void error(Object originator, LogMessage msg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(msg.toString());
        logger.error("From: {} -> {}", originator, msg);
    }

    @Override
    public void fatal(Object originator, String logMsg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(logMsg);
        logger.error("From: {} -> {}", originator, logMsg);;
    }

    @Override
    public void fatal(Object originator, LogMessage msg) {
        if (bacmmanLogger!=null) bacmmanLogger.setMessage(msg.toString());
        logger.error("From: {} -> {}", originator, msg);
    }

    @Override
    public String getLogFile() {
        return null;
    }

}
