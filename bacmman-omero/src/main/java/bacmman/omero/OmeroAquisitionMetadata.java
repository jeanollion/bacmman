package bacmman.omero;

import bacmman.core.OmeroGatewayI;
import bacmman.utils.FileIO;
import omero.RLong;
import omero.RType;
import omero.cmd.*;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class OmeroAquisitionMetadata {
    public static final Logger logger = LoggerFactory.getLogger(OmeroAquisitionMetadata.class);
    final long imageID;
    Map<String, RType> globalMetadata;
    public OmeroAquisitionMetadata(long imageID) {
        this.imageID = imageID;
    }
    public boolean fetch(OmeroGatewayI gateway) {
        try {
            OriginalMetadataRequest omr = new OriginalMetadataRequest(imageID);
            CmdCallbackI cmd = gateway.gateway().submit(gateway.securityContext(), omr);
            OriginalMetadataResponse rsp = (OriginalMetadataResponse) cmd.loop(5, 500);
            globalMetadata= rsp.globalMetadata;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    public void writeToFile(String outputFile) {
        List<Map.Entry<String, RType>> entries = new ArrayList<>(globalMetadata.entrySet());
        Collections.sort(entries, Comparator.comparing(Map.Entry::getKey));
        FileIO.writeToFile(outputFile, entries, e -> e.getKey()+"="+ TypeConverter.convert(e.getValue()));
    }
    public void append(JSONObject jsonObject) {
        globalMetadata.forEach((k, v)->jsonObject.put(k, TypeConverter.convert(v)));
    }
    public List<Long> extractTimepoints(boolean relative) {
        List<Date> dates = globalMetadata.entrySet().stream()
                .filter(e-> e.getKey().startsWith("timestamp"))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> ((RLong)(e.getValue())).getValue())
                .map(Date::new).collect(Collectors.toList());
        if (dates.isEmpty()) return Collections.emptyList();
        if (relative) {
            Date ref= dates.get(0);
            return dates.stream().map(d -> (d.getTime() - ref.getTime())).collect(Collectors.toList());
        }
        else return dates.stream().map(Date::getTime).collect(Collectors.toList());
    }
}
