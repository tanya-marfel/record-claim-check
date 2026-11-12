# Binary arithmetic for the envelope codec

A guide to the number handling in `EnvelopeCodec` and `ClaimCheckEnvelope`: signed and unsigned bytes, masking, field sizes and offsets, and the mistakes that are easy to make. The layout it refers to is in [ADR 0002](../adr/0002-binary-claim-check-envelope.md).

## The envelope layout and its arithmetic

Big-endian. The header before the storage ID is 46 bytes.

| Field | Size | Index |
| --- | --- | --- |
| Magic `KRC1` | 4 | 0 to 3 |
| Version | 1 | 4 |
| Original size | 8 | 5 to 12 |
| SHA-256 | 32 | 13 to 44 |
| Storage ID length | 1 (unsigned) | 45 |
| Storage ID | 1 to 253 | 46 onward |

```
HEADER_SIZE      = 4 + 1 + 8 + 32 + 1  = 46
ID_LENGTH_OFFSET = 4 + 1 + 8 + 32      = 45     (the sum of the fields before it)
total length     = HEADER_SIZE + storage ID length
```

Compute offsets from the field sizes, in code and in tests, so they follow the layout when it changes. When the layout gains or loses a field, change the sum and everything that depends on it moves together.

Allocate exactly `HEADER_SIZE + storageIdBytes.length` and write exactly that many bytes. Writing a different number of bytes than the header reserves causes `BufferOverflowException` on encode (too many) or a wrong length on decode (too few or too many read).

## Bytes are signed in Java

Java has no unsigned byte. A `byte` is always read as -128 to 127. The same 8 bits mean different numbers depending on how they are read:

```
bits 11001000  ->  as signed byte:   -56
               ->  as unsigned:      200
```

Signed values use two's complement. The top bit is a sign bit, worth -128. For `11001000`: -128 + 64 + 8 = -56.

The envelope format says some fields are unsigned. That is a rule of the format. Java does not know it, so the code must convert explicitly.

## Widening copies the sign bit

The bits in memory never change. The meaning changes when a `byte` widens to an `int`, because Java fills the new high bits with copies of the sign bit (sign extension):

```
byte b = (byte) 0xC8;      // bits 11001000, value -56
int  i = b;                // 11111111 11111111 11111111 11001000 = -56
```

## Masking restores the unsigned value

`& 0xFF` keeps the lowest 8 bits and clears the rest, which removes the copied sign bits:

```
11111111 11111111 11111111 11001000    the widened byte (-56)
00000000 00000000 00000000 11111111    0xFF, the mask
00000000 00000000 00000000 11001000    result = 200
```

```java
int length = buf.get() & 0xFF;                  // 0 to 255
```

The mask width always matches the read width:

| Read | Mask | Range |
| --- | --- | --- |
| `buf.get()` | `& 0xFF` | 0 to 255 |
| `buf.getShort()` | `& 0xFFFF` | 0 to 65535 |
| `buf.getInt()` | `& 0xFFFFFFFFL` (as a `long`) | 0 to 4294967295 |

The named methods do the same thing and show intent:

```java
Byte.toUnsignedInt(buf.get())            // same as buf.get() & 0xFF
Short.toUnsignedInt(buf.getShort())      // same as buf.getShort() & 0xFFFF
Integer.toUnsignedLong(buf.getInt())     // same as buf.getInt() & 0xFFFFFFFFL
```

A mask is needed only where the format defines a field as unsigned and Java would read it as signed. The size field is a `long` that must not be negative, so it is read as signed and checked with `size < 0`.

### Values that show the problem

| Length byte | Stored as signed byte | After `& 0xFF` |
| --- | --- | --- |
| 127 | 127 | 127 |
| 128 | -128 | 128 |
| 200 | -56 | 200 |
| 253 | -3 | 253 |
| 254 | -2 | 254 |
| 255 | -1 | 255 |

127 and 128 are the boundary. Up to 127 a missing mask goes unnoticed. From 128 the value turns negative. A negative length passed to `new byte[length]` throws `NegativeArraySizeException`. The envelope bytes come from a Kafka record and are untrusted, so a bad length must become a clean `InvalidEnvelopeException`, never another exception type.

## Read and write with the same width

The length field is one byte, so the read and the write use `get` and `put`:

```java
.put((byte) storageIdBytes.length)        // write: 1 byte
int length = buf.get() & 0xFF;            // read:  1 byte
```

Common mistakes and what they do:

| Mistake | Effect |
| --- | --- |
| `putShort((byte) n)` | The cast changes only the value. `putShort` still writes 2 bytes, so encode overflows the buffer. |
| `buf.getShort() & 0xFF` | Reads 2 bytes and masks to 8 bits. The real length (high byte) is discarded and the first character of the ID is consumed. |
| `buf.get()` with no mask | A length of 128 or more comes out negative. |
| `& 0xFF` on a `getShort()` | Wrong mask width. Use `& 0xFFFF`. |

Example of the second mistake, with the ID `orders` (length 6):

```
index:  45   46   47   48   49   50   51
bytes:  06   6F   72   64   65   72   73
        len  'o'  'r'  'd'  'e'  'r'  's'

getShort()  reads 06 6F  = 0x066F = 1647
1647 & 0xFF = 0x6F       = 111       the letter 'o', not the length
```

The length check `111 != remaining` then rejects every valid envelope.

## Casting an int to a byte

An array element or a `put` of a single byte needs an explicit cast. The cast keeps the lowest 8 bits:

```java
value[ID_LENGTH_OFFSET] = (byte) 254;     // stored bits 11111110
```

Use this in tests to write a deliberately bad length byte into an otherwise valid envelope.

## Big-endian

Multi-byte numbers are stored with the most significant byte first. `ByteBuffer` is big-endian by default.

```
putLong(1_048_577)  =  0x0000000000100001
bytes:  00 00 00 00 00 10 00 01
```

Reading a multi-byte field in the wrong order gives a different number: the two bytes `00 01` are 1 in big-endian and 256 in little-endian. Do not change the order on one side only.

## Checking lengths against the data

Never allocate a buffer from a declared size without checking it against the bytes that exist.

- Check `value.length >= HEADER_SIZE` before reading the header, so reads cannot run past the end.
- Check the storage ID length is 1 to 253 and equals `buf.remaining()`. This also rejects trailing bytes.
- Read the length before allocating the ID array. Only allocate `new byte[length]` after those checks pass, so an attacker cannot make you allocate memory with a forged length.
- Do not allocate memory from the original size field. It can be as large as `Long.MAX_VALUE`. The filter compares it with `maxValueBytes` before it downloads anything.

## Decoding text safely

Bytes of the storage ID must be strict UTF-8. The default `new String(bytes, UTF_8)` replaces malformed bytes with a replacement character and gives no error. Use a decoder that reports:

```java
StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
```

Because the allowed characters (`a-z`, `0-9`, `-`, `.`) are ASCII, the length in characters equals the length in UTF-8 bytes. A non-ASCII ID is rejected by the character check.

## A record's compact constructor

In a record's compact constructor the parameters are local variables. The fields are assigned at the end. An instance method called from the constructor reads the fields, which are still `null`:

```java
public ClaimCheckEnvelope {
    validateSha256();                  // reads this.sha256, which is null here -> NullPointerException
}
```

Make validation helpers `static` and pass the values in. A static method cannot read the fields, so the mistake cannot return:

```java
public ClaimCheckEnvelope {
    validateSha256(sha256);
    sha256 = sha256.clone();           // assigns the parameter; Java stores it in the field
}

private static void validateSha256(byte[] sha256) { ... }
```

## Testing the arithmetic

Test the boundaries, not only a typical value:

- Storage ID lengths 1, 127, 128, 200, 253 round trip. 128 is the first that needs the mask.
- Length bytes 0, 254 and 255 are rejected.
- Length 253 passes and 254 fails in the record as well as in the codec.
- Every truncation of a valid envelope is rejected, from magic-only up to one byte short.
- Trailing bytes after the ID are rejected.
- Encode is compared with a hand-written byte array, not only with `decode`, so a symmetric bug in both cannot hide.

A fixed digest of distinct values (1 to 32) makes swapped or shifted bytes visible. All zeros would not.

Build a bad input by encoding a valid envelope and changing one byte:

```java
byte[] value = validEncoded("orders");
value[ID_LENGTH_OFFSET] = (byte) 254;
assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
```

Only the changed field can be the cause, and the test follows the layout if it changes.

JUnit parameterized tests keep these cases short: `@ValueSource(ints = {...})` for lengths, `@ValueSource(strings = {...})` for bad IDs, and `@MethodSource` for generated data such as every truncation length or a 254-character ID.

## Further reading

- [Two's complement](https://en.wikipedia.org/wiki/Two%27s_complement): how signed numbers are stored.
- [Sign extension](https://en.wikipedia.org/wiki/Sign_extension): the widening step.
- [Java Language Specification, chapter 5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html): conversions, including widening primitive conversion (5.1.2).
- [`Byte` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Byte.html): `toUnsignedInt` and related methods.
- [`ByteBuffer` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html): byte order, absolute and relative reads and writes.
