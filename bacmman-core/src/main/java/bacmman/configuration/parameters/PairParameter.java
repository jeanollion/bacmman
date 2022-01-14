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

import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class PairParameter<A extends Parameter<A>, B extends Parameter<B>> extends ContainerParameterImpl<PairParameter<A, B>> {
    protected A param1;
    protected B param2;
    public PairParameter(String name, A param1, B param2) {
        super(name);
        this.param1 = param1;
        this.param2 = param2;
        initChildList();
    }
    public A getParam1() {return param1;}
    public B getParam2() {return param2;}
    @Override
    protected void initChildList() {
        super.initChildren(param1, param2);
    }
    
    @Override
    public PairParameter<A, B> duplicate() {
        PairParameter<A, B> res =  new PairParameter<>(name, param1.duplicate(), param2.duplicate());
        transferStateArguments(this, res);
        return res;
    }
    /*@Override
    public String toString() {
        return name + ":" + Utils.toStringList(children);
    }*/

    @Override
    public JSONArray toJSONEntry() {
        if (children==null) initChildList();
        return JSONUtils.toJSONArrayMap(children);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) return;
        if (children==null) initChildList();
        if (JSONUtils.isJSONArrayMap(jsonEntry)) JSONUtils.fromJSONArrayMap(children, (JSONArray)jsonEntry);
        else JSONUtils.fromJSON(children, (JSONArray)jsonEntry);
    }
    
}
