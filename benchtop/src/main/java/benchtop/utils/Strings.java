package benchtop.utils;

import java.util.Collection;
import java.util.Iterator;


/**
 * @author Huascar Sanchez
 */
public class Strings {

  /**
   * Utility class. Not meant to be instantiated.
   */
  private Strings(){
    throw new Error("Utility class");
  }

  /**
   * Joins a collection of objects, which override toString, using a
   * delimiter.
   */
  public static <T> String joinCollection(String delimiter, Collection<T> entries){
    return java6LikeJoin(delimiter, entries);
  }

  /**
   * Generates an array of strings from an array of objects.
   *
   * @param objects array of objects to stringify.
   * @return an array of strings.
   */
  public static String[] generateArrayOfStrings(Object[] objects) {
    String[] result = new String[objects.length];
    int i = 0;

    for (Object o : objects) {
      result[i++] = o.toString();
    }
    return result;
  }

  /**
   * Generates an array of strings from a collection of objects.
   *
   * @param objects collection of objects to stringify.
   * @return an array of strings.
   */
  public static String[] generateArrayOfStrings(Collection<?> objects) {
    return generateArrayOfStrings(objects.toArray());
  }

  /**
   * Implements a generic method for joining a collection of objects. This
   * method is intended to work on Java6+ versions.
   */
  private static <T> String java6LikeJoin(String delimiter, Collection<T> data){
    final Iterator<T> iterator = data.iterator();
    final StringBuilder stringBuilder = new StringBuilder();

    if (iterator.hasNext()) {
      stringBuilder.append(iterator.next());

      while (iterator.hasNext()) {
        stringBuilder.append(delimiter).append(iterator.next());
      }
    }

    return stringBuilder.toString();
  }

}