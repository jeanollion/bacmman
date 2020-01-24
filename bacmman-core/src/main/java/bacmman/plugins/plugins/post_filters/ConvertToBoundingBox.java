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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;

import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class ConvertToBoundingBox implements PostFilter, Hint {

    @Override
    public String getHintText() {
        return "Transforms a segmented object into a rectangular object corresponding to the bounding box of the region, modified by user-defined parameters in each direction. <br /> Similar to ExtendBounds module but with more options";
    }
    public enum METHOD { CONSTANT_SIZE, EXTEND_ON_SIDES, FROM_OBJECT_CLASS, NO_MODIFICATION }
    public enum OUT_OF_BOUNDS_CONDITION { TRIM, KEEP_SIZE, KEEP_CENTER}
    public enum CONSTANT_SIZE_CONDITION { ALWAYS, TARGET_SIZE_IS_SMALLER, TARGET_SIZE_IS_LARGER}

    EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Method", METHOD.values(), METHOD.CONSTANT_SIZE, false);
    BoundedNumberParameter addBefore = new BoundedNumberParameter("Add Before", 0, 8, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds at the beginning");
    BoundedNumberParameter addAfter = new BoundedNumberParameter("Add After", 0, 8, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds at the end");
    EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = new EnumChoiceParameter<>("Out-of-bound", OUT_OF_BOUNDS_CONDITION.values(), OUT_OF_BOUNDS_CONDITION.KEEP_SIZE, false).setEmphasized(true).setHint("In case the extended bounding box is out of the bound of the parent image:<br /><ul><li><em>Trim: </em> The bound is simply set to the bound of the parent image</li><li><em>Keep Global Size: </em> The bounds are translated in order to fit in the parent image and keep a constant size</li><li><em>Keep Center: </em> Bounds are equally trimmed on both sides so that the original bound is still in the middle of the new bounds</li></ul>");
    BoundedNumberParameter size = new BoundedNumberParameter("Size", 0, 0, 1, null).setEmphasized(true).setHint("Final size of the bounding box in this axis");
    EnumChoiceParameter<CONSTANT_SIZE_CONDITION> constantSizeCondition = new EnumChoiceParameter<>("Condition", CONSTANT_SIZE_CONDITION.values(), CONSTANT_SIZE_CONDITION.ALWAYS, false).setEmphasized(false).setHint("<ul><li><em>TARGET_SIZE_IS_SMALLER:</em>Target size is set to the object only if smaller than the object's size along this axis</li><li><em>TARGET_SIZE_IS_LARGER:</em>Target size is set to the object only if larger than the object's size along this axis</li></ul>");
    BooleanParameter useParentBounds = new BooleanParameter("Use parent bounds", true).setHint("If true, modified bounds are limited to the object's parent bounds. If false, limits are those of the whole pre-processed image ");
    ParentObjectClassParameter refObjectClass = new ParentObjectClassParameter("Reference Object class").setHint("Reference object class used to compute bounds. Bounding box in chosen axis will be used");
    ConditionalParameter methodCond = new ConditionalParameter(method).setEmphasized(true)
            .setActionParameters(METHOD.CONSTANT_SIZE.toString(), size, outOfBound, constantSizeCondition)
            .setActionParameters(METHOD.EXTEND_ON_SIDES.toString(), addBefore, addAfter, outOfBound)
            .setActionParameters(METHOD.FROM_OBJECT_CLASS.toString(), refObjectClass);

    SimpleListParameter<ConditionalParameter> axisCond = new SimpleListParameter<>("Per axis modification", 0, methodCond)
            .setNewInstanceNameFunction((l, idx)-> "XYZ".charAt(idx)+" axis").setEmphasized(true).setMaxChildCount(3).setChildrenNumber(2);

    private static void modifyBoundingBox(SegmentedObject parent, MutableBoundingBox toModify, BoundingBox parentBounds, ConditionalParameter axisParameter, int axisNumber) {
        METHOD method = ((EnumChoiceParameter<METHOD>)axisParameter.getActionableParameter()).getSelectedEnum();
        List<Parameter> parameters = axisParameter.getActionParameters(method.toString());
        switch (method) {
            case NO_MODIFICATION:
            default:
                return;
            case CONSTANT_SIZE: {
                BoundedNumberParameter size = (BoundedNumberParameter) parameters.get(0);
                EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = (EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>) parameters.get(1);
                EnumChoiceParameter<CONSTANT_SIZE_CONDITION> constantSizeCondition = (EnumChoiceParameter<CONSTANT_SIZE_CONDITION>) parameters.get(2);
                int newSize = size.getValue().intValue();
                int delta = newSize - getDim(toModify, axisNumber);
                logger.trace("modifiy axis: {}, oob: {}, cond: {}, target: {}, delta: {}", axisNumber, outOfBound.getSelectedEnum(), constantSizeCondition.getSelectedEnum(), newSize, delta);
                if (delta == 0 ) return;
                switch (constantSizeCondition.getSelectedEnum()) {
                    case TARGET_SIZE_IS_LARGER:
                        if (delta < 0) return;
                        break;
                    case TARGET_SIZE_IS_SMALLER:
                        if (delta>0) return;
                        break;
                }

                int vMin = getBound(toModify, axisNumber, true) - (delta - delta / 2 );
                int vMax = getBound(toModify, axisNumber, false) + delta / 2;
                logger.trace("new vmin: {}, new vmax: {}", vMin, vMax);
                setBound(toModify, vMin, axisNumber, true);
                setBound(toModify, vMax, axisNumber, false);
                ensureOutOfBound(toModify, parentBounds, axisNumber, outOfBound.getSelectedEnum());
                return;
            }
            case EXTEND_ON_SIDES: {
                BoundedNumberParameter addBefore = (BoundedNumberParameter) parameters.get(0);
                BoundedNumberParameter addAfter = (BoundedNumberParameter) parameters.get(1);
                EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = (EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>) parameters.get(2);
                int vMin = getBound(toModify, axisNumber, true) - addBefore.getValue().intValue();
                int vMax = getBound(toModify, axisNumber, false) + addAfter.getValue().intValue();
                setBound(toModify, vMin, axisNumber, true);
                setBound(toModify, vMax, axisNumber, false);
                ensureOutOfBound(toModify, parentBounds, axisNumber, outOfBound.getSelectedEnum());
                return;
            }
            case FROM_OBJECT_CLASS: {
                ParentObjectClassParameter refObjectClass = (ParentObjectClassParameter)parameters.get(0);
                int refOC = refObjectClass.getSelectedClassIdx();
                Offset refOff = parent.getBounds().duplicate().reverseOffset();
                int[] minAndMax = parent.getChildren(refOC)
                        .map(SegmentedObject::getBounds)
                        .map(o->o.duplicate().translate(refOff))
                        .filter(o->BoundingBox.intersect(o, toModify)) // intersect with current bounds
                        .reduce(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE},
                                (d , b) -> {
                                    if (d[0]>getMin(b, axisNumber)) d[0] = getMin(b, axisNumber);
                                    if (d[1]<getMax(b, axisNumber)) d[1] = getMax(b, axisNumber);
                                    return d;
                                },
                                (d1, d2)->{
                                    if (d1[0]>d2[0]) d1[0] = d2[0];
                                    if (d1[1]<d2[1]) d1[1] = d2[1];
                                    return d1;
                                });
                if (minAndMax[0]!=Integer.MAX_VALUE) {
                    setBound(toModify, minAndMax[0], axisNumber, true);
                    setBound(toModify, minAndMax[1], axisNumber, false);
                }
            }
        }
    }
    private static void ensureOutOfBound(MutableBoundingBox bb, BoundingBox parentBB, int axis, OUT_OF_BOUNDS_CONDITION oob) {
        int minLimit = getBound(parentBB, axis, true);
        int vmin = getBound(bb, axis, true);
        int maxLimit = getBound(parentBB, axis, false);
        int vmax = getBound(bb, axis, false);
        switch(oob) {
            case TRIM: {
                if (vmin < minLimit) setBound(bb, minLimit, axis, true);
                if (vmax > maxLimit) setBound(bb, maxLimit, axis, false);
                return;
            }
            case KEEP_SIZE:
                if (vmin<minLimit && vmax>maxLimit) throw new RuntimeException("Modified bounds could not fit into parent bound on "+"XYZ".charAt(axis)+" axis");
                if (vmin < minLimit) translate(bb, minLimit - vmin, axis);
                if (vmax > maxLimit) translate(bb, maxLimit - vmax, axis);
                break;
            case KEEP_CENTER:
                if (vmin < minLimit) {
                    setBound(bb, minLimit, axis, true);
                    int newVmax = vmax - (minLimit - vmin);
                    setBound(bb, newVmax, axis, false);
                }
                if (vmax < maxLimit) {
                    setBound(bb, maxLimit, axis, false);
                    int newVmin = vmin + (vmax - maxLimit);
                    setBound(bb, newVmin, axis, true);
                }
                if (getBound(bb, axis, true)>=getBound(bb, axis, false)) throw new RuntimeException("Negative size on "+"XYZ".charAt(axis)+" axis");
                break;
        }
    }
    private static void translate(MutableBoundingBox bb, int value, int axis) {
        switch (axis) {
            case 0:
                bb.translate(value, 0, 0);
                return;
            case 1:
                bb.translate(0, value, 0);
                return;
            case 2:
                bb.translate(0, 0, value);
                return;
        }
    }
    private  static int getDim(BoundingBox bb, int axis) {
        switch (axis) {
            case 0:
                return bb.sizeX();
            case 1:
                return bb.sizeY();
            case 2:
                return bb.sizeZ();
            default:
                throw new IllegalArgumentException("Invalid axis");
        }
    }
    private  static int getMin(BoundingBox bb, int axis) {
        switch (axis) {
            case 0:
                return bb.xMin();
            case 1:
                return bb.yMin();
            case 2:
                return bb.zMin();
            default:
                throw new IllegalArgumentException("Invalid axis");
        }
    }
    private  static int getMax(BoundingBox bb, int axis) {
        switch (axis) {
            case 0:
                return bb.xMax();
            case 1:
                return bb.yMax();
            case 2:
                return bb.zMax();
            default:
                throw new IllegalArgumentException("Invalid axis");
        }
    }
    private static void setBound(MutableBoundingBox bb, int value, int axis, boolean min) {
        switch (axis) {
            case 0:
                if (min) bb.setxMin(value);
                else bb.setxMax(value);
                return;
            case 1:
                if (min) bb.setyMin(value);
                else bb.setyMax(value);
                return;
            case 2:
                if (min) bb.setzMin(value);
                else bb.setzMax(value);
                return;
        }
    }
    private static int getBound(BoundingBox bb, int axis, boolean min) {
        switch (axis) {
            case 0:
                if (min) return bb.xMin();
                else return bb.xMax();
            case 1:
                if (min) return bb.yMin();
                else return bb.yMax();
            case 2:
                if (min) return bb.zMin();
                else return bb.zMax();
            default:
                throw new IllegalArgumentException("Invalid axis");
        }
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        BoundingBox parentBds = useParentBounds.getSelected() ? new SimpleBoundingBox(parent.getBounds()).resetOffset() : parent.getRoot().getBounds(); // post-filter: relative to parent
        childPopulation.ensureEditableRegions();
        childPopulation.getRegions().forEach(r->{
            MutableBoundingBox bds = new MutableBoundingBox(r.getBounds());
            List<ConditionalParameter> axisParam = this.axisCond.getChildren();
            for (int axis = 0; axis<axisParam.size(); ++axis) modifyBoundingBox(parent, bds, parentBds, axisParam.get(axis), axis);
            r.setMask(new BlankMask(new SimpleImageProperties(bds, r.getScaleXY(), r.getScaleZ())));
        });
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{axisCond, useParentBounds};
    }
    
}
