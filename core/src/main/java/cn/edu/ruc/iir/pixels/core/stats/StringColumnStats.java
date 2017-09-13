package cn.edu.ruc.iir.pixels.core.stats;

/**
 * pixels
 *
 * @author guodong
 */
public interface StringColumnStats extends ColumnStats
{
    String getMinimum();

    String getMaximum();

    /**
     * Get the total length of all strings
     * @return the sum (total length)
     * */
    long getSum();
}