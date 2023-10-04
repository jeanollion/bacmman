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

/**
 * An actionable parameter is used by a conditional parameter: when its content is modified, it should also call the setObjectValue method of the conditional parameter, and its UI object should call the ConfigurationModel.nodeStructureChanged method to update the tree
 * @author Jean Ollion
 */
public interface ActionableParameter<V, P extends ActionableParameter<V, P>> extends Parameter<P>, ChoosableParameter<P>, Listenable<P> {
    V getValue();
    void setValue(V value);
    void setConditionalParameter(ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> cond);
    ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> getConditionalParameter();
}
