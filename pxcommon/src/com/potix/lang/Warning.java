/* Warning.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Mon Oct 27 10:20:17     2003, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2003 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.lang;

import com.potix.mesg.Messageable;

/**
 * Represents an exception is a warning rather than error.
 * {@link #getCode} is the warnig code.
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public interface Warning extends Expectable, Messageable {
}
