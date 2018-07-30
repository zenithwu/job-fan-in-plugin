package org.lonkar.jobfanin.bdp;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Util
{
    private static final Logger logger = LoggerFactory.getLogger(Util.class);

    public static final boolean isEmpty(Object obj)
    {
        if (obj == null) {
            return true;
        }
        if ((obj instanceof String)) {
            return "".equals(String.valueOf(obj).trim());
        }
        if (obj.getClass().isArray()) {
            return Array.getLength(obj) == 0;
        }
        if ((obj instanceof Collection)) {
            return ((Collection)obj).isEmpty();
        }
        if ((obj instanceof Map)) {
            return ((Map)obj).isEmpty();
        }
        if ((obj instanceof Number));
        return false;
    }
}