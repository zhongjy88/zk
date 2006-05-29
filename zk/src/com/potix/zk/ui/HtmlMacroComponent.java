/* HtmlMacroComponent.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Fri Apr 14 13:54:13     2006, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2006 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.zk.ui;

import java.util.Map;
import java.util.HashMap;

import com.potix.zk.ui.ext.PostCreate;
import com.potix.zk.ui.ext.DynamicPropertied;

/**
 * The implemetation of a macro component upon HTML.
 *
 * <p>Generally, a macro component is created automatically by ZK loader.
 * If a developer wants to create it manually, it has to instantiate from
 * the correct class, and then invoke {@link #postCreate}.
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public class HtmlMacroComponent extends HtmlBasedComponent
implements PostCreate, IdSpace, DynamicPropertied {
	private final Map _attrs = new HashMap(7);

	public HtmlMacroComponent() {
		_attrs.put("includer", this);
	}

	//-- PostCreate --//
	public void postCreate() {
		getDesktop().getExecution().createComponents(
			getDefinition().getMacroURI(this), this, _attrs);
	}

	//-- DynamicPropertied --//
	public boolean hasDynamicProperty(String name) {
		return _attrs.containsKey(name);
	}
	public Object getDynamicProperty(String name) {
		return _attrs.get(name);
	}
	public void setDynamicProperty(String name, Object value)
	throws WrongValueException {
		_attrs.put(name, value);
	}
}
