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
import bacmman.data_structure.Region;
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
    public enum METHOD { CONSTANT_SIZE, CONSTANT_SIZE_START, CONSTANT_SIZE_END, EXTEND_ON_SIDES, FROM_OBJECT_CLASS, NO_MODIFICATION }
    public enum OUT_OF_BOUNDS_CONDITION { TRIM, KEEP_SIZE, KEEP_CENTER}
    public enum CONSTANT_SIZE_CONDITION { ALWAYS, TARGET_SIZE_IS_SMALLER, TARGET_SIZE_IS_LARGER}

    EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Method", METHOD.values(), METHOD.CONSTANT_SIZE);
    BoundedNumberParameter addBefore = new BoundedNumberParameter("Add Before", 0, 8, null, null).setEmphasized(true).setHint("Number of pixel to add to bounds at the beginning");
    BoundedNumberParameter addAfter = new BoundedNumberParameter("Add After", 0, 8, null, null).setEmphasized(true).setHint("Number of pixel to add to bounds at the end");
    EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = new EnumChoiceParameter<>("Out-of-bound", OUT_OF_BOUNDS_CONDITION.values(), OUT_OF_BOUNDS_CONDITION.KEEP_SIZE).setEmphasized(true).setHint("In case the extended bounding box is out of the bound of the parent image:<br /><ul><li><em>Trim: </em> The bound is simply set to the bound of the parent image</li><li><em>Keep Global Size: </em> The bounds are translated in order to fit in the parent image and keep a constant size</li><li><em>Keep Center: </em> Bounds are equally trimmed on both sides so that the original bound is still in the middle of the new bounds</li></ul>");
    BoundedNumberParameter size = new BoundedNumberParameter("Size", 0, 0, 1, null).setEmphasized(true).setHint("Final size of the bounding box in this axis");
    EnumChoiceParameter<CONSTANT_SIZE_CONDITION> constantSizeCondition = new EnumChoiceParameter<>("Condition", CONSTANT_SIZE_CONDITION.values(), CONSTANT_SIZE_CONDITION.ALWAYS).setEmphasized(false).setHint("<ul><li><em>TARGET_SIZE_IS_SMALLER:</em>Target size is set to the object only if smaller than the object's size along this axis</li><li><em>TARGET_SIZE_IS_LARGER:</em>Target size is set to the object only if larger than the object's size along this axis</li></ul>");
    BooleanParameter useParentBounds = new BooleanParameter("Use parent bounds", true).setHint("If true, modified bounds are limited to the object's parent bounds. If false, limits are those of the whole pre-processed image ");
    ParentObjectClassParameter refObjectClass = new ParentObjectClassParameter("Reference Object class").setHint("Reference object class used to compute bounds. Bounding box in chosen axis will be used");
    ConditionalParameter<METHOD> methodCond = new ConditionalParameter<>(method).setEmphasized(true)
            .setActionParameters(METHOD.CONSTANT_SIZE, size, outOfBound, constantSizeCondition)
            .setActionParameters(METHOD.CONSTANT_SIZE_START, size, outOfBound, constantSizeCondition)
            .setActionParameters(METHOD.CONSTANT_SIZE_END, size, outOfBound, constantSizeCondition)
            .setActionParameters(METHOD.EXTEND_ON_SIDES, addBefore, addAfter, outOfBound)
            .setActionParameters(METHOD.FROM_OBJECT_CLASS, refObjectClass);

    SimpleListParameter<ConditionalParameter<METHOD>> axisCond = new SimpleListParameter<>("Per axis modification", methodCond)
            .setNewInstanceNameFunction((l, idx)-> "XYZ".charAt(idx)+" axis")
            .setEmphasized(true).setMaxChildCount(3).setChildrenNumber(2)
            .addValidationFunction(l -> !l.getActivatedChildren().isEmpty());

    public ConvertToBoundingBox setUseParentBounds(boolean useParentBounds) {
        this.useParentBounds.setSelected(useParentBounds);
        return this;
    }

    public ConvertToBoundingBox resetAxisModifications() {
        axisCond.removeAllElements();
        return this;
    }

    public ConvertToBoundingBox addConstantAxisModification(METHOD method, int size, OUT_OF_BOUNDS_CONDITION oob, CONSTANT_SIZE_CONDITION constantSizeCondition) {
        if (method == null || method.equals(METHOD.EXTEND_ON_SIDES) || method.equals(METHOD.FROM_OBJECT_CLASS)) throw new IllegalArgumentException("Invalid method");
        ConditionalParameter<METHOD> c = axisCond.createChildInstance();
        c.setActionValue(method);
        List<Parameter> params = c.getCurrentParameters();
        ((BoundedNumberParameter)params.get(0)).setValue(size);
        ((EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>)params.get(1)).setSelectedEnum(oob);
        ((EnumChoiceParameter<CONSTANT_SIZE_CONDITION>)params.get(2)).setSelectedEnum(constantSizeCondition);
        axisCond.insert(c);
        return this;
    }

    public ConvertToBoundingBox addExtendAxisModification(int addBefore, int addAfter, OUT_OF_BOUNDS_CONDITION oob) {
        ConditionalParameter<METHOD> c = axisCond.createChildInstance();
        c.setActionValue(METHOD.EXTEND_ON_SIDES);
        List<Parameter> params = c.getCurrentParameters();
        ((BoundedNumberParameter)params.get(0)).setValue(addBefore);
        ((BoundedNumberParameter)params.get(1)).setValue(addAfter);
        ((EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>)params.get(2)).setSelectedEnum(oob);
        axisCond.insert(c);
        return this;
    }

    public ConvertToBoundingBox addFromObjectClassAxisModification(int objectClassIdx) {
        ConditionalParameter<METHOD> c = axisCond.createChildInstance();
        c.setActionValue(METHOD.FROM_OBJECT_CLASS);
        List<Parameter> params = c.getCurrentParameters();
        ((ParentObjectClassParameter)params.get(0)).setSelectedClassIdx(objectClassIdx);
        axisCond.insert(c);
        return this;
    }

    private static void modifyBoundingBox(SegmentedObject parent, MutableBoundingBox toModify, BoundingBox parentBounds, ConditionalParameter<METHOD> axisParameter, int axisNumber) {
        METHOD method = ((EnumChoiceParameter<METHOD>)axisParameter.getActionableParameter()).getSelectedEnum();
        List<Parameter> parameters = axisParameter.getParameters(method);
        switch (method) {
            case NO_MODIFICATION:
            default:
                return;
            case CONSTANT_SIZE:
            case CONSTANT_SIZE_START:
            case CONSTANT_SIZE_END: {
                BoundedNumberParameter size = (BoundedNumberParameter) parameters.get(0);
                EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = (EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>) parameters.get(1);
                EnumChoiceParameter<CONSTANT_SIZE_CONDITION> constantSizeCondition = (EnumChoiceParameter<CONSTANT_SIZE_CONDITION>) parameters.get(2);
                int newSize = size.getValue().intValue();
                int deltaSize = newSize - getDim(toModify, axisNumber);
                //logger.debug("modifiy axis: {}, oob: {}, cond: {}, target: {}, delta: {}", axisNumber, outOfBound.getSelectedEnum(), constantSizeCondition.getSelectedEnum(), newSize, deltaSize);
                if (deltaSize == 0 ) return;
                switch (constantSizeCondition.getSelectedEnum()) {
                    case TARGET_SIZE_IS_LARGER:
                        if (deltaSize < 0) return;
                        break;
                    case TARGET_SIZE_IS_SMALLER:
                        if (deltaSize>0) return;
                        break;
                }
                int vMin, vMax;
                switch (method) {
                    case CONSTANT_SIZE:
                    default: {
                        vMin = getBound(toModify, axisNumber, true) - (deltaSize - deltaSize / 2);
                        vMax = getBound(toModify, axisNumber, false) + deltaSize / 2;
                        break;
                    } case CONSTANT_SIZE_END: {
                        vMin = getBound(toModify, axisNumber, true) - deltaSize;
                        vMax = getBound(toModify, axisNumber, false);
                        break;
                    } case CONSTANT_SIZE_START: {
                        vMin = getBound(toModify, axisNumber, true);
                        vMax = getBound(toModify, axisNumber, false) + deltaSize;
                    }
                }

                setBound(toModify, vMin, axisNumber, true);
                setBound(toModify, vMax, axisNumber, false);
                ensureOutOfBound(toModify, parentBounds, axisNumber,  outOfBound.getSelectedEnum());
                //logger.debug("new vmin: {}, new vmax: {}", getBound(toModify, axisNumber, true), getBound(toModify, axisNumber, false));
                return;
            }
            case EXTEND_ON_SIDES: {
                BoundedNumberParameter addBefore = (BoundedNumberParameter) parameters.get(0);
                BoundedNumberParameter addAfter = (BoundedNumberParameter) parameters.get(1);
                EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION> outOfBound = (EnumChoiceParameter<OUT_OF_BOUNDS_CONDITION>) parameters.get(2);
                int vMin = getBound(toModify, axisNumber, true) - addBefore.getValue().intValue();
                int vMax = getBound(toModify, axisNumber, false) + addAfter.getValue().intValue();
                if (vMax<=vMin) throw new IllegalArgumentException("EXTEND_ON_SIDES result in negative size object");
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
                if (vmin < minLimit && vmax > maxLimit) { // trim anyway
                    setBound(bb, minLimit, axis, true);
                    setBound(bb, maxLimit, axis, false);
                } else if (vmin < minLimit) {
                    translate(bb, minLimit - vmin, axis);
                    if (getBound(bb, axis, false) > maxLimit) setBound(bb, maxLimit, axis, false);
                } else if (vmax > maxLimit) {
                    translate(bb, maxLimit - vmax, axis);
                    if (getBound(bb, axis, true) < minLimit) setBound(bb, minLimit, axis, true);
                }
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

    private static int getAxis(String name) {
        char n = name.charAt(0);
        if (n=='X') return 0;
        else if (n=='Y') return 1;
        else if (n=='Z') return 2;
        else throw new RuntimeException("Invalid axis name: "+name);
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        BoundingBox parentBds = useParentBounds.getSelected() ? new SimpleBoundingBox(parent.getBounds()).resetOffset() : (BoundingBox)parent.getRoot().getBounds().duplicate().translateReverse(parent.getBounds()); // post-filter: relative to parent
        childPopulation.ensureEditableRegions();
        childPopulation.getRegions().forEach(r->{
            MutableBoundingBox bds = new MutableBoundingBox(r.getBounds());
            this.axisCond.getActivatedChildren().forEach(axisParam -> modifyBoundingBox(parent, bds, parentBds, axisParam, getAxis(axisParam.getName())));
            r.setMask(new BlankMask(new SimpleImageProperties(bds, r.getScaleXY(), r.getScaleZ())));
        });
        return childPopulation;
    }

    public Region transform(SegmentedObject parent, Region region) {
        BoundingBox bds = transformToBox(parent, region);
        Region res = new Region(new BlankMask(new SimpleImageProperties(bds, region.getScaleXY(), region.getScaleZ())), region.getLabel(), region.is2D());
        res.setIsAbsoluteLandmark(region.isAbsoluteLandMark());
        return res;
    }

    public BoundingBox transformToBox(SegmentedObject parent, Region region) {
        BoundingBox parentBds = useParentBounds.getSelected() ? new SimpleBoundingBox(parent.getBounds()).resetOffset() : (BoundingBox)parent.getRoot().getBounds().duplicate().translateReverse(parent.getBounds()); // post-filter: relative to parent
        MutableBoundingBox bds = new MutableBoundingBox(region.getBounds());
        if (region.isAbsoluteLandMark()) bds.translateReverse(parent.getBounds());
        this.axisCond.getActivatedChildren().forEach(axisParam -> modifyBoundingBox(parent, bds, parentBds, axisParam, getAxis(axisParam.getName())));
        if (region.isAbsoluteLandMark()) bds.translate(parent.getBounds());
        return bds;
    }

    public ImageProperties transformToImageProperties(SegmentedObject parent, Region region) {
        BoundingBox box = transformToBox(parent, region);
        return new SimpleImageProperties(box, region.getScaleXY(), region.getScaleZ());
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{axisCond, useParentBounds};
    }
    
}
