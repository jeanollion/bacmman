package bacmman.image;

import java.util.Objects;

public class ImageCoordinate {
    int frame, channel, z;

    public ImageCoordinate duplicate() {
        return new ImageCoordinate(frame, channel, z);
    }

    public ImageCoordinate(int... fcz) {
        if (fcz.length>0) frame = fcz[0];
        if (fcz.length>1) channel = fcz[1];
        if (fcz.length>2) z = fcz[2];
    }
    public void setFrame(int frame) {
        this.frame = frame;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public int getFrame() {
        return frame;
    }

    public int getChannel() {
        return channel;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImageCoordinate)) return false;
        ImageCoordinate that = (ImageCoordinate) o;
        return frame == that.frame && channel == that.channel && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(frame, channel, z);
    }

    @Override
    public String toString() {
        return "ImageCoordinate{" +
                "frame=" + frame +
                ", channel=" + channel +
                ", z=" + z +
                '}';
    }
}
