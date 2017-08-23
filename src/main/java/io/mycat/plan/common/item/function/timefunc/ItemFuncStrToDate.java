package io.mycat.plan.common.item.function.timefunc;

import io.mycat.plan.common.item.FieldTypes;
import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.ItemFunc;
import io.mycat.plan.common.time.DateTimeFormat;
import io.mycat.plan.common.time.MySQLTime;
import io.mycat.plan.common.time.MySQLTimestampType;
import io.mycat.plan.common.time.MyTime;

import java.util.ArrayList;
import java.util.List;

public class ItemFuncStrToDate extends ItemTemporalHybridFunc {
    private MySQLTimestampType cached_timestamp_type;

    /**
     * @param args
     */
    public ItemFuncStrToDate(List<Item> args) {
        super(new ArrayList<Item>());
    }

    @Override
    public final String funcName() {
        return "str_to_date";
    }

    @Override
    public void fixLengthAndDec() {
        maybeNull = true;
        cached_field_type = FieldTypes.MYSQL_TYPE_DATETIME;
        cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_DATETIME;
        if (args.get(1).basicConstItem()) {
            String format = args.get(1).valStr();
            if (!args.get(1).nullValue)
                fix_from_format(format);
        }
    }

    /**
     * Set type of datetime value (DATE/TIME/...) which will be produced
     * according to format string.
     *
     * @param format format string
     * @note We don't process day format's characters('D', 'd', 'e') because day
     * may be a member of all date/time types.
     * @note Format specifiers supported by this function should be in sync with
     * specifiers supported by extract_date_time() function.
     */
    private void fix_from_format(String format) {
        String timePartFrms = "HISThiklrs";
        String datePartFrms = "MVUXYWabcjmvuxyw";
        boolean datePartUsed = false, timePartUsed = false, fracSecondUsed = false;
        int val = 0;
        int end = format.length();
        char[] cs = format.toCharArray();

        for (; val != end; val++) {
            if (cs[val] == '%' && val + 1 != end) {
                val++;
                if (cs[val] == 'f')
                    fracSecondUsed = timePartUsed = true;
                else if (!timePartUsed && timePartFrms.indexOf(cs[val]) >= 0)
                    timePartUsed = true;
                else if (!datePartUsed && datePartFrms.indexOf(cs[val]) >= 0)
                    datePartUsed = true;
                if (datePartUsed && fracSecondUsed) {
                    /*
                     * frac_second_used implies time_part_used, and thus we
                     * already have all types of date-time components and can
                     * end our search.
                     */
                    cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_DATETIME;
                    cached_field_type = FieldTypes.MYSQL_TYPE_DATETIME;
                    fixLengthAndDecAndCharsetDatetime(MyTime.MAX_DATETIME_WIDTH, MyTime.DATETIME_MAX_DECIMALS);
                    return;
                }
            }
        }

        /* We don't have all three types of date-time components */
        if (fracSecondUsed) /* TIME with microseconds */ {
            cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_TIME;
            cached_field_type = FieldTypes.MYSQL_TYPE_TIME;
            fixLengthAndDecAndCharsetDatetime(MyTime.MAX_TIME_FULL_WIDTH, MyTime.DATETIME_MAX_DECIMALS);
        } else if (timePartUsed) {
            if (datePartUsed) /* DATETIME, no microseconds */ {
                cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_DATETIME;
                cached_field_type = FieldTypes.MYSQL_TYPE_DATETIME;
                fixLengthAndDecAndCharsetDatetime(MyTime.MAX_DATETIME_WIDTH, 0);
            } else /* TIME, no microseconds */ {
                cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_TIME;
                cached_field_type = FieldTypes.MYSQL_TYPE_TIME;
                fixLengthAndDecAndCharsetDatetime(MyTime.MAX_TIME_WIDTH, 0);
            }
        } else /* DATE */ {
            cached_timestamp_type = MySQLTimestampType.MYSQL_TIMESTAMP_DATE;
            cached_field_type = FieldTypes.MYSQL_TYPE_DATE;
            fixLengthAndDecAndCharsetDatetime(MyTime.MAX_DATE_WIDTH, 0);
        }
    }

    @Override
    protected boolean val_datetime(MySQLTime ltime, long fuzzy_date) {
        DateTimeFormat dateTimeFormat = new DateTimeFormat();
        String val = args.get(0).valStr();
        String format = args.get(1).valStr();
        boolean nullDate = false;
        if (args.get(0).nullValue || args.get(1).nullValue)
            nullDate = true;
        if (!nullDate) {
            nullValue = false;
            dateTimeFormat.format = format;
            if (MyTime.extract_date_time(dateTimeFormat, val, ltime, cached_timestamp_type, "datetime")
                    || ((fuzzy_date & MyTime.TIME_NO_ZERO_DATE) != 0
                    && (ltime.year == 0 || ltime.month == 0 || ltime.day == 0)))
                nullDate = true;
        }
        if (!nullDate) {
            ltime.time_type = cached_timestamp_type;
            if (cached_timestamp_type == MySQLTimestampType.MYSQL_TIMESTAMP_TIME && ltime.day != 0) {
                /*
                 * Day part for time type can be nonzero value and so we should
                 * add hours from day part to hour part to keep valid time
                 * value.
                 */
                ltime.hour += ltime.day * 24;
                ltime.day = 0;
            }
            return false;
        }

        null_date:
        if (val != null && (fuzzy_date & MyTime.TIME_NO_ZERO_DATE) != 0 /* warnings */) {
            LOGGER.warn("str_to_date value:" + val + " is wrong value for format:" + format);
        }
        return (nullValue = true);
    }

    @Override
    public ItemFunc nativeConstruct(List<Item> realArgs) {
        return new ItemFuncStrToDate(realArgs);
    }
}
