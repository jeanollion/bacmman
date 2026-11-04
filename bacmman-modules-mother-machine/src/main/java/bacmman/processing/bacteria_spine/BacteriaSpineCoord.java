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
package bacmman.processing.bacteria_spine;

/**
 * Coordinate of a point in the spine coordinate system of a bacteria: curvilinear coordinate (distance along the spine from the first pole), radial coordinate (signed distance to the spine along the lateral direction), spine length and spine width at the point.
 * In 3D, a second radial coordinate is the signed distance along the second lateral direction (B = T x N of the frame of the spine), or, with the 2.5D coordinate system, the signed distance along z to the mid-plane of the bacteria (and the half-thickness of the bacteria at the point is also stored, so that the height can be normalized when the coordinate is projected in another bacteria). Both are 0 in 2D.
 * @author Jean Ollion
 */
public class BacteriaSpineCoord {
    public final double[] coords = new double[6];
    public BacteriaSpineCoord duplicate() {
        BacteriaSpineCoord dup = new BacteriaSpineCoord();
        System.arraycopy(coords, 0, dup.coords, 0, coords.length);
        return dup;
    }
    public double curvilinearCoord(boolean normalized) {
        return normalized?coords[0]/coords[2] : coords[0];
    }
    public double getProjectedCurvilinearCoord(double newSpineLength, BacteriaSpineLocalizer.PROJECTION proj) {
        switch(proj) {
            case PROPORTIONAL:
            default:
                return curvilinearCoord(true) * newSpineLength;
            case NEAREST_POLE:
                if (curvilinearCoord(true)<=0.5) { // upper pole
                    //logger.debug("upper pole: new l {}, old l {}, res: {}",newSpineLength , spineLength(), spineCoord(false));
                    return curvilinearCoord(false);
                } else { // lower pole
                    //logger.debug("lower pole: new l {}, old l {}, coord: {}, delta: {}, res: {}",newSpineLength , spineLength(), spineCoord(false), spineLength() - spineCoord(false), newSpineLength-(spineLength() - spineCoord(false)));
                    return newSpineLength-(spineLength() - curvilinearCoord(false));
                }
            case IDENTITY:
                return curvilinearCoord(false);
        }
    }
    public double spineLength() {
        return coords[2];
    }
    public double radialCoord(boolean normalized) {
        return normalized ? coords[1]/coords[3] : coords[1];
    } 
    public double spineRadius() {
        return coords[3];
    }
    /**
     * @return 3D: signed distance to the spine along the second lateral direction (B = T x N). 0 in 2D
     */
    public double radialCoord2() {
        return coords[4];
    }
    public BacteriaSpineCoord setRadialCoord2(double radialCoord2) {
        coords[4] = radialCoord2;
        return this;
    }
    /**
     * @return 2.5D coordinate system: half-thickness (along z) of the bacteria at the point (XY-pixel units), 0 otherwise
     */
    public double halfThickness() {
        return coords[5];
    }
    public BacteriaSpineCoord setHalfThickness(double halfThickness) {
        coords[5] = halfThickness;
        return this;
    }
    /**
     * @return distance to the spine (in 2D: absolute value of the radial coordinate)
     */
    public double distanceToSpine() {
        return Math.sqrt(coords[1]*coords[1] + coords[4]*coords[4]);
    }
    public BacteriaSpineCoord setCurvilinearCoord(double curvilinearCoord) {
        coords[0] = curvilinearCoord;
        return this;
    }
    public BacteriaSpineCoord setSpineLength(double spineLengthd) {
        coords[2] = spineLengthd;
        return this;
    }
    public BacteriaSpineCoord setRadialCoord(double radialCoord) {
        coords[1] = radialCoord;
        return this;
    }
    public BacteriaSpineCoord setSpineRadius(double spineRadius) {
        coords[3] = spineRadius;
        return this;
    }
    /**
     * modifies the spine coordinate so that it can be projected in a daughter cell 
     * @param divisionProportion size of daughter cell / sum of daughter cells sizes
     * @param upperCell wheter the projection will be done in the upper daughter cell or not
     * @return same modified object
     */
    public BacteriaSpineCoord setDivisionPoint(double divisionProportion, boolean upperCell) {
        if (!upperCell) setCurvilinearCoord(curvilinearCoord(false)-(1-divisionProportion)*spineLength()); // shift coord of the size of the upper cell
        setSpineLength(spineLength() * divisionProportion);
        return this;
    }
    
    @Override
    public String toString() {
        return new StringBuilder().append("curvilinear:").append(curvilinearCoord(false)).append("/").append(spineLength()).append(" radial:").append(radialCoord(false)).append(coords[4]!=0 ? ", "+coords[4] : "").append("/").append(spineRadius()).toString();
    }
}
