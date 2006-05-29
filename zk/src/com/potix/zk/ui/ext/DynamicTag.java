/* DynamicTag.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Mon Oct  3 22:03:28     2005, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.zk.ui.ext;

import com.potix.zk.ui.WrongValueException;

/**
 * Represents a component is used to represent a broad range of tags.
 *
 * <p>For example, com.potix.zhtml.Raw is used to generate any HTML tags
 * that doesn't have the ZK counterpart. Rason: there are too many
 * HTML extended tags available and developers might choose to use them.
 *
 * <p>How to use:
 * <ol>
 * <li>First, extends a class from {@link com.potix.zk.ui.AbstractComponent}
 * and implements this interface</li>
 * <li>Declares the class in lang.xml by enclosing it with &lt;dynamic-tag&gt;.
 * Then, any tag that ZK doesn't understand, will use the class.</li>
 * </ol>
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public interface DynamicTag extends DynamicPropertied {
	/** Sets the tag name.
	 */
	public void setTag(String tagname) throws WrongValueException;
	/** Returns whether the specified tag is allowed.
	 */
	public boolean hasTag(String tagname);
}
