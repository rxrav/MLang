package lang.m.parser.ast;

/**
 * A compile-time literal value.
 *
 * <p>The {@code kind} field determines how {@code value} is interpreted
 * and which JVM instruction the code generator emits:
 *
 * <table>
 *   <tr><th>kind</th>    <th>value type</th>   <th>JVM instruction</th></tr>
 *   <tr><td>int</td>     <td>Integer</td>        <td>ICONST / BIPUSH / SIPUSH / LDC</td></tr>
 *   <tr><td>long</td>    <td>Long</td>           <td>LCONST / LDC</td></tr>
 *   <tr><td>float</td>   <td>Float</td>          <td>FCONST / LDC</td></tr>
 *   <tr><td>double</td>  <td>Double</td>         <td>DCONST / LDC</td></tr>
 *   <tr><td>bool</td>    <td>Boolean</td>        <td>ICONST_0 / ICONST_1</td></tr>
 *   <tr><td>string</td>  <td>String</td>         <td>LDC</td></tr>
 *   <tr><td>null</td>    <td>null</td>           <td>ACONST_NULL</td></tr>
 * </table>
 *
 * @param kind  the literal kind (see table above)
 * @param value the parsed Java value object, or {@code null} for kind {@code "null"}
 */
public record LiteralNode(String kind, Object value) implements Node {}
