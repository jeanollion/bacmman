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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.json.simple.JSONArray;
import bacmman.utils.JSONUtils;

/**
 *
 * @author Jean Ollion
 */
public class GroupParameter extends ContainerParameterImpl<GroupParameter> {
    public GroupParameter(String name, Parameter... parameters) {
        super(name);
        this.children = Arrays.asList(parameters);
        initChildList();
    }
    public GroupParameter(String name, Collection<Parameter> parameters) {
        super(name);
        this.children = new ArrayList<>(parameters);
        initChildList();
    }

    protected GroupParameter(String name) {
        super(name);
    }
    
    @Override
    protected void initChildList() {
        super.initChildren(children);
    }
    
    @Override
    public GroupParameter duplicate() {
        List<Parameter> dup = ParameterUtils.duplicateList(children);
        GroupParameter res =  new GroupParameter(name, dup);
        transferStateArguments(this, res);
        return res;
    }
    /*@Override
    public String toString() {
        return name + ":" + Utils.toStringList(children);
    }*/

    @Override
    public JSONArray toJSONEntry() {
        return JSONUtils.toJSONArrayMap(children);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) return;
        if (JSONUtils.isJSONArrayMap(jsonEntry)) JSONUtils.fromJSONArrayMap(children, (JSONArray)jsonEntry);
        else JSONUtils.fromJSON(children, (JSONArray)jsonEntry);
    }
    
}
