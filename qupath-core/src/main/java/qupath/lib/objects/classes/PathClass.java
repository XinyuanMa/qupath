/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.objects.classes;

import java.io.Serializable;

import qupath.lib.common.ColorTools;

/**
 * Representation of an object's classification - which can be defined using any unique string identifier (e.g. tumour, lymphocyte, gland, benign, malignant).
 * <p>
 * In order to keep the construction of PathClasses under control, they should be generated using the static methods within PathClassFactory.
 * 
 * @see PathClassFactory
 * 
 * @author Pete Bankhead
 *
 */
public class PathClass implements Comparable<PathClass>, Serializable {
	
	private static final long serialVersionUID = 1L;

	private static String defaultName = "Unclassified";
	private static Integer DEFAULT_COLOR = ColorTools.makeRGB(64, 64, 64);
	
	private final PathClass parentClass;
	private final String name;
	private Integer colorRGB;

	PathClass() {
		parentClass = null;
		name = null;
		colorRGB = null;
	}

	/**
	 * This constructor should <i>not<i> be called explicitly; rather, use {@link PathClassFactory}. 
	 * <p>
	 * Only one instance of a PathClass should exist for any given name and list of ancestors.
	 * 
	 * @param parent
	 * @param name
	 * @param colorRGB
	 */
	PathClass(PathClass parent, String name, Integer colorRGB) {
		if (parent != null && name == null)
			throw new IllegalArgumentException("Cannot create a derived PathClass with name == null");
		this.parentClass = parent;
		this.name = name;
		if (colorRGB == null)
			this.colorRGB = DEFAULT_COLOR;
		else
			this.colorRGB = colorRGB;
	}
	
	PathClass(String name, Integer colorRGB) {
		this(null, name, colorRGB);
	}
	
	public PathClass getParentClass() {
		return parentClass;
	}
	
	public boolean isDerivedClass() {
		return parentClass != null;
	}
	
	/**
	 * Returns TRUE if this class, or any ancestor class, is equal to the specified parent class.
	 * 
	 * @param parentClass
	 * @return
	 */
	public boolean isDerivedFrom(PathClass parentClass) {
		PathClass pathClass = this;
		while (pathClass != null) {
			if (pathClass.equals(parentClass))
				return true;
			pathClass = pathClass.parentClass;
		}
		return false;
	}
	
	
	/**
	 * Returns {@code true} if this class is equal to the specified child class, 
	 * or an ancestor of that class.
	 * 
	 * @param childClass
	 * @return
	 */
	public boolean isAncestorOf(PathClass childClass) {
		PathClass pathClass = childClass;
		while (pathClass != null) {
			if (this.equals(pathClass))
				return true;
			pathClass = pathClass.parentClass;
		}
		return false;
	}
	
	
	/**
	 * Get the 'base' class, i.e. trace back through getParentClass() until no parent is available.
	 * 
	 * For a PathClass with no parent, this just returns itself.
	 * 
	 * @return
	 */
	public PathClass getBaseClass() {
		PathClass temp = this;
		while (temp.getParentClass() != null)
			temp = temp.getParentClass();
		return temp;
	}
	
	public void setColor(Integer colorRGB) {
		if (colorRGB == null || !colorRGB.equals(this.colorRGB))
			this.colorRGB = colorRGB;
	}
	
	public Integer getColor() {
		return colorRGB;
	}
	
	public String getName() {
		return name;
	}
	
	static String derivedClassToString(PathClass parent, String name) {
		return parent == null ? name : parent.toString() + ": " + name;
	}
	
	@Override
	public String toString() {
		if (name == null)
			return defaultName;
		if (isDerivedClass())
			return derivedClassToString(parentClass, name);
		else
			return name;
	}
	
	/**
	 * A PathClass is valid if its name is not null.
	 * <p>
	 * This should generally the case, but a single (invalid) PathClass with a null name 
	 * can be used to indicate the absence of a classification; however, it should not be assigned 
	 * to any object.  Rather, objects should be assigned either a valid PathClass or null to indicate 
	 * that they have no classification.
	 * 
	 * @return
	 */
	public boolean isValid() {
		return name != null;
	}

	/**
	 * This is now equivalent to {@code this.toString().compareTo(o.toString())}.
	 * <p>
	 * Note that in previous versions (&lt; 0.1.2), the comparison was made based on the name only.
	 * <p>
	 * This could result in unexpected behavior whenever comparing with equality and using 
	 * derived {@code PathClass} objects, because only the (final) name part was being compared 
	 * and this could potentially result in classifications (wrongly) being considered equal 
	 * (e.g. "Tumor: Positive" and "Stroma: Positive").
	 * <p>
	 * This was most significant when working with Groovy, where {@code == } is replaced by {@code compareTo}.
	 */
	@Override
	public int compareTo(PathClass o) {
		return toString().compareTo(o.toString());
		// Old behavior (v0.1.2) - can give unexpected results with Groovy == comparisons
//		if (name == null) {
//			if (o.getName() == null)
//				return 0;
//			else
//				return -1;
//		} else if (o.getName() == null)
//			return 1;
//		return name.compareTo(o.getName());
	}
	
}