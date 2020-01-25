package scodec.bits

import org.scalacheck.{Arbitrary, Gen}
import Arbitraries._
import org.scalatest.matchers.should.Matchers._
import org.scalacheck._
import java.util.UUID

class BitVectorTest extends BitsSuite {
  implicit val arbitraryBitVector: Arbitrary[BitVector] = Arbitrary {
    Gen.oneOf(flatBytes, balancedTrees, splitVectors, concatSplitVectors, bitStreams)
  }

  test("hashCode/equals") {
    forAll { (b: BitVector, b2: BitVector, m: Long) =>
      assert((b.take(m) ++ b.drop(m)).hashCode == b.hashCode)
      if (b.take(3) == b2.take(3)) {
        // kind of weak, since this will only happen 1/8th of attempts on average
        assert(b.take(3).hashCode == b2.take(3).hashCode)
      }
    }
  }

  test("=== consistent with ==") {
    forAll { (b: BitVector, b2: BitVector) =>
      assert((b == b2) == (b === b2))
    }
  }

  test("compact is a no-op for already compact bit vectors") {
    val b = BitVector(0x80, 0x90)
    assert((b.compact.underlying eq b.compact.underlying) == true)
    assert((b.toByteVector eq b.toByteVector) == true)
    val b2 = b.drop(8).align // also make sure this works if we're talking about a byte-aligned slice
    assert((b2.compact.underlying eq b2.compact.underlying) == true)
    assert((b2.toByteVector eq b2.toByteVector) == true)
  }

  test("equals/take/drop stack safety") {
    forAll(hugeBitStreams) { b =>
      assert(b == b) // this exercises take/drop
    }
  }

  test("hashCode/take/drop stack safety") {
    forAll(hugeBitStreams) { b =>
      assert(b.hashCode == b.hashCode)
    }
  }

  test("size stack safety") {
    forAll(hugeBitStreams) { b =>
      assert(b.size == b.size)
    }
  }

  test("acquire stack safety for lazy BitVector") {
    val nats = BitVector.unfold(0)(i => Some(BitVector.high(1000) -> (i + 1)))
    assert(nats.acquire(100000).isRight == true)
  }

  val bitVectorWithTakeIndex = Arbitrary.arbitrary[BitVector].flatMap { bits =>
    Gen.choose(0L, bits.size + 1).map((bits, _))
  }

  test("acquire/take consistency") {
    def check(bits: BitVector, n: Long): Unit = {
      val b = bits.acquire(n)
      b match {
        case Left(_)   => bits.size should be < n
        case Right(hd) => assert(hd == bits.take(n))
      }
      assert(bits.acquireThen(n)(Left(_), Right(_)) == b)
      assert(bits.consumeThen(n)(Left(_), (a, b) => Right((b, a))) == bits.consume(n)(Right(_)))
      ()
    }

    forAll(bitVectorWithTakeIndex) {
      case (bits, ind) =>
        check(bits, ind)
        check(bits, ind * 2)
    }
  }

  test("1-bit vectors") {
    assert(BitVector.zero.head == false)
    assert(BitVector.one.head == true)
    assert(BitVector.bit(false).head == false)
    assert(BitVector.bit(true).head == true)
  }

  test("construction via high") {
    assert(BitVector.high(1).toByteVector == ByteVector(0x80))
    assert(BitVector.high(2).toByteVector == ByteVector(0xc0))
    assert(BitVector.high(3).toByteVector == ByteVector(0xe0))
    assert(BitVector.high(4).toByteVector == ByteVector(0xf0))
    assert(BitVector.high(5).toByteVector == ByteVector(0xf8))
    assert(BitVector.high(6).toByteVector == ByteVector(0xfc))
    assert(BitVector.high(7).toByteVector == ByteVector(0xfe))
    assert(BitVector.high(8).toByteVector == ByteVector(0xff))
    assert(BitVector.high(9).toByteVector == ByteVector(0xff, 0x80))
    assert(BitVector.high(10).toByteVector == ByteVector(0xff, 0xc0))
  }

  test("empty toByteVector") {
    assert(BitVector.empty.toByteVector == ByteVector.empty)
  }

  test("apply") {
    val vec = BitVector(ByteVector(0xf0, 0x0f))
    vec(0) should be(true)
    vec(1) should be(true)
    vec(2) should be(true)
    vec(3) should be(true)
    vec(4) should be(false)
    vec(5) should be(false)
    vec(6) should be(false)
    vec(7) should be(false)
    vec(8) should be(false)
    vec(9) should be(false)
    vec(10) should be(false)
    vec(11) should be(false)
    vec(12) should be(true)
    vec(13) should be(true)
    vec(14) should be(true)
    vec(15) should be(true)
  }

  test("getByte") {
    forAll { (x: BitVector) =>
      val bytes = x.bytes
      val aligned = x.align
      (0L until ((x.size + 7) / 8)).foreach { i =>
        assert(bytes(i) == x.getByte(i))
        assert(aligned.getByte(i) == x.getByte(i))
      }
    }
  }

  test("updated") {
    val vec = BitVector.low(16)
    vec.set(6).get(6) should be(true)
    vec.set(10).get(10) should be(true)
    vec.set(10).clear(10).get(10) should be(false)
  }

  test("drop") {
    assert(BitVector.high(8).drop(4).toByteVector == ByteVector(0xf0))
    assert(BitVector.high(8).drop(3).toByteVector == ByteVector(0xf8))
    assert(BitVector.high(10).drop(3).toByteVector == ByteVector(0xfe))
    assert(BitVector.high(10).drop(3) == BitVector.high(7))
    assert(BitVector.high(12).drop(3).toByteVector == ByteVector(0xff, 0x80))
    assert(BitVector.empty.drop(4) == BitVector.empty)
    assert(BitVector.high(4).drop(8) == BitVector.empty)
    assert(BitVector.high(8).drop(-20) == BitVector.high(8))
    forAll { (x: BitVector, n: Long) =>
      val m = if (x.nonEmpty) (n % x.size).abs else 0
      assert(x.compact.drop(m).toIndexedSeq.take(4) == x.toIndexedSeq.drop(m.toInt).take(4))
      assert(x.compact.drop(m).compact.toIndexedSeq.take(4) == x.toIndexedSeq.drop(m.toInt).take(4))
    }
  }

  test("take/drop") {
    assert(BitVector.high(8).take(4).toByteVector == ByteVector(0xf0))
    assert(BitVector.high(8).take(4) == BitVector.high(4))
    assert(BitVector.high(8).take(5).toByteVector == ByteVector(0xf8))
    assert(BitVector.high(8).take(5) == BitVector.high(5))
    assert(BitVector.high(10).take(7).toByteVector == ByteVector(0xfe))
    assert(BitVector.high(10).take(7) == BitVector.high(7))
    assert(BitVector.high(12).take(9).toByteVector == ByteVector(0xff, 0x80))
    assert(BitVector.high(12).take(9) == BitVector.high(9))
    assert(BitVector.high(4).take(100).toByteVector == ByteVector(0xf0))
    assert(BitVector.high(12).take(-100) == BitVector.empty)
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      x.depth should be <= 18
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0
      assert((x.take(m) ++ x.drop(m)).compact == x)
      assert(x.take(m + n).compact.take(n) == x.take(n))
      assert(x.drop(m + n).compact == x.drop(m).compact.drop(n))
      assert(x.drop(n).take(m) == x.drop(n).take(m))
      assert(x.drop(n).take(m).toIndexedSeq == BitVector
        .bits(x.drop(n).toIndexedSeq)
        .take(m)
        .toIndexedSeq)
    }
  }

  test("dropRight") {
    assert(BitVector.high(12).clear(0).dropRight(4).toByteVector == ByteVector(0x7f))
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0
      assert(x.dropRight(m).dropRight(n) == x.dropRight(m + n))
      assert(x.dropRight(m) == x.take(x.size - m))
    }
  }

  test("takeRight") {
    assert(BitVector.high(12).clear(0).takeRight(4).toByteVector == ByteVector(0xf0))
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0
      assert(x.takeRight(m.max(n)).takeRight(n).compact == x.takeRight(n))
      assert(x.takeRight(m) == x.drop(x.size - m))
    }
  }

  test("compact") {
    forAll { (x: BitVector) =>
      assert(x.compact == x)
      x.force.depth should be < 36
    }
  }

  test("depth") {
    // check that building a million byte vector via repeated snocs leads to balanced tree
    val N = 1000000
    val bits = (0 until N).map(n => BitVector(n)).foldLeft(BitVector.empty)(_ ++ _)
    bits.depth should be < 36
  }

  test("++") {
    assert((BitVector.low(7) ++ BitVector.high(1)).toByteVector == ByteVector(1: Byte))
    assert((BitVector.high(8) ++ BitVector.high(8)).toByteVector == ByteVector(-1: Byte, -1: Byte))
    assert((BitVector.high(4) ++ BitVector.low(4)).toByteVector == ByteVector(0xf0))
    assert((BitVector.high(4) ++ BitVector.high(4)).toByteVector == ByteVector(-1: Byte))
    assert((BitVector.high(4) ++ BitVector.high(5)).toByteVector == ByteVector(-1.toByte.toInt, 0x80))
    assert((BitVector.low(2) ++ BitVector.high(4)).toByteVector == ByteVector(0x3c))
    assert((BitVector.low(2) ++ BitVector.high(4) ++ BitVector.low(2)).toByteVector == ByteVector(0x3c))
    forAll { (x: BitVector, y: BitVector) =>
      assert((x ++ y).compact.toIndexedSeq == (x.toIndexedSeq ++ y.toIndexedSeq))
    }
  }

  test("b.take(n).drop(n) == b") {
    implicit val intGen = Arbitrary(Gen.choose(0, 10000))
    forAll { (xs: List[Boolean], n0: Int, m0: Int) =>
      whenever(xs.nonEmpty) {
        val n = n0.abs % xs.size
        val m = m0.abs % xs.size
        assert(xs.drop(m).take(n) == xs.take(m + n).drop(m))
      }
    }
    forAll { (xs: BitVector, n0: Long) =>
      val m = if (xs.nonEmpty) n0 % xs.size else 0
      assert((xs.take(m) ++ xs.drop(m)).compact == xs)
    }
  }

  test("<<") {
    assert((BitVector.high(8) << 0).toByteVector == ByteVector(0xff))
    assert((BitVector.high(8) << 4).toByteVector == ByteVector(0xf0))
    assert((BitVector.high(10) << 1).toByteVector == ByteVector(0xff, 0x80))
    assert((BitVector.high(10) << 3).toByteVector == ByteVector(0xfe, 0x00))
    assert((BitVector.high(32) << 16).toByteVector == ByteVector(0xff, 0xff, 0, 0))
    assert((BitVector.high(32) << 15).toByteVector == ByteVector(0xff, 0xff, 0x80, 0))
  }

  test(">>") {
    assert((BitVector.high(8) >> 8) == BitVector.high(8))
  }

  test(">>>") {
    assert((BitVector.high(8) >>> 7).toByteVector == ByteVector(0x01))
  }

  test("rotations") {
    assert(bin"10101".rotateRight(3) == bin"10110")
    assert(bin"10101".rotateLeft(3) == bin"01101")
    forAll { (b: BitVector, n: Long) =>
      assert(b.rotateLeft(b.size) == b)
      assert(b.rotateRight(b.size) == b)
      val n0 = if (b.nonEmpty) n % b.size else n
      assert(b.rotateRight(n0).rotateLeft(n0) == b)
      assert(b.rotateLeft(n0).rotateRight(n0) == b)
    }
  }

  test("padTo") {
    assert(BitVector.high(2).padTo(8).toByteVector == ByteVector(0xc0))
    assert(BitVector.high(16).padTo(32).toByteVector == ByteVector(0xff, 0xff, 0, 0))
  }

  test("~") {
    assert(~BitVector.high(12) == BitVector.low(12))
    assert(~BitVector.low(12) == BitVector.high(12))
    assert(~BitVector(10, 10) == BitVector(245, 245))
    assert(~BitVector(245, 245) == BitVector(10, 10))
  }

  test("&") {
    assert((BitVector.high(16) & BitVector.high(16)) == BitVector.high(16))
    assert((BitVector.low(16) & BitVector.high(16)) == BitVector.low(16))
    assert((BitVector.high(16) & BitVector.low(16)) == BitVector.low(16))
    assert((BitVector.low(16) & BitVector.low(16)) == BitVector.low(16))
    assert((BitVector.high(16) & BitVector.high(9)) == BitVector.high(9))
  }

  test("|") {
    assert((BitVector.high(16) | BitVector.high(16)) == BitVector.high(16))
    assert((BitVector.low(16) | BitVector.high(16)) == BitVector.high(16))
    assert((BitVector.high(16) | BitVector.low(16)) == BitVector.high(16))
    assert((BitVector.low(16) | BitVector.low(16)) == BitVector.low(16))
    assert((BitVector.high(16) | BitVector.low(9)) == BitVector.high(9))
  }

  test("^") {
    assert((BitVector.high(16) ^ BitVector.high(16)) == BitVector.low(16))
    assert((BitVector.low(16) ^ BitVector.high(16)) == BitVector.high(16))
    assert((BitVector.high(16) ^ BitVector.low(16)) == BitVector.high(16))
    assert((BitVector.low(16) ^ BitVector.low(16)) == BitVector.low(16))
    assert((BitVector.high(16) ^ BitVector.low(9)) == BitVector.high(9))
    assert((BitVector(10, 245) ^ BitVector(245, 10)) == BitVector.high(16))
  }

  test("toIndexedSeq") {
    assert(BitVector.high(8).toIndexedSeq == List.fill(8)(true))
  }

  test("reverse") {
    assert(BitVector(0x03).reverse == BitVector(0xc0))
    assert(BitVector(0x03, 0x80).reverse == BitVector(0x01, 0xc0))
    assert(BitVector(0x01, 0xc0).reverse == BitVector(0x03, 0x80))
    assert(BitVector(0x30).take(4).reverse == BitVector(0xc0).take(4))
    forAll { (bv: BitVector) =>
      assert(bv.reverse.reverse == bv)
    }
  }

  test("reverseByteOrder") {
    assert(BitVector(0x00, 0x01).reverseByteOrder == BitVector(0x01, 0x00))
    // Double reversing should yield original if size is divisible by 8
    forAll(genBitVector(500, 0)) { (bv: BitVector) =>
      assert(bv.reverseByteOrder.reverseByteOrder == bv)
    }
    forAll { (bv: BitVector) =>
      assert(bv.reverseByteOrder.invertReverseByteOrder == bv)
    }
  }

  test("toHex") {
    assert(BitVector(0x01, 0x02).toHex == "0102")
    assert(BitVector(0x01, 0x02).drop(4).toHex == "102")
    assert(BitVector(0x01, 0x02).drop(5).toHex == "204")
    forAll { (bv: BitVector) =>
      if (bv.size % 8 == 0 || bv.size % 8 > 4) assert(bv.toHex == bv.toByteVector.toHex)
      else assert(bv.toHex == bv.toByteVector.toHex.init)
    }
  }

  test("fromHexDescriptive") {
    assert(BitVector.fromHexDescriptive("0x012") == Right(BitVector(0x01, 0x20).take(12)))
    assert(BitVector.fromHexDescriptive("0x01gg") == Left("Invalid hexadecimal character 'g' at index 4"))
    forAll { (bv: BitVector) =>
      val x = bv.padTo((bv.size + 3) / 4 * 4)
      assert(BitVector.fromValidHex(x.toHex) == x)
    }
    assert(BitVector.fromHexDescriptive("00 01 02 03") == Right(BitVector(0x00, 0x01, 0x02, 0x03)))
  }

  test("fromValidHex") {
    assert(BitVector.fromValidHex("0x012") == BitVector(0x01, 0x20).take(12))
    an[IllegalArgumentException] should be thrownBy { BitVector.fromValidHex("0x01gg"); () }
  }

  test("toBin") {
    assert(BitVector(0x01, 0x02, 0xff).toBin == "000000010000001011111111")
    assert(BitVector(0x01, 0x02, 0xff).drop(3).toBin == "000010000001011111111")
    forAll { (bv: BitVector) =>
      assert(bv.toBin == bv.toByteVector.toBin.take(bv.size.toInt))
    }
  }

  test("fromBinDescriptive") {
    forAll { (bv: BitVector) =>
      assert(BitVector.fromBinDescriptive(bv.toBin) == Right(bv))
    }
    assert(BitVector.fromBinDescriptive("0102") == Left("Invalid binary character '2' at index 3"))
    assert(BitVector.fromBinDescriptive("0000 0001 0010 0011") == Right(BitVector(0x01, 0x23)))
  }

  test("fromValidBin") {
    forAll { (bv: BitVector) =>
      assert(BitVector.fromValidBin(bv.toBin) == bv)
    }
    an[IllegalArgumentException] should be thrownBy { BitVector.fromValidBin("0x0102"); () }
  }

  test("bin string interpolator") {
    assert(bin"0010" == BitVector(0x20).take(4))
    val x = BitVector.fromValidBin("10")
    assert(bin"00$x" == BitVector(0x20).take(4))
    """bin"asdf"""" shouldNot compile
  }

  test("grouped + concatenate") {
    forAll { (bv: BitVector) =>
      if (bv.isEmpty) {
        assert(bv.grouped(1).toList == Nil)
      } else if (bv.size < 3) {
        assert(bv.grouped(bv.size).toList == List(bv))
      } else {
        assert(bv.grouped(bv.size / 3).toList.foldLeft(BitVector.empty) { (acc, b) =>
          acc ++ b
        } == bv)
      }
    }
  }

  test("population count") {
    forAll { (bv: BitVector) =>
      val cnt = bv.toIndexedSeq.foldLeft(0) { (acc, b) =>
        if (b) acc + 1 else acc
      }
      assert(bv.populationCount == cnt)
    }
  }

  test("indexOfSlice/containsSlice/startsWith") {
    forAll { (bv: BitVector, m0: Long, n0: Long) =>
      val m = if (bv.nonEmpty) (m0 % bv.size).abs else 0L
      val n = if (bv.nonEmpty) (n0 % bv.size).abs else 0L
      val slice = bv.slice(m.min(n), m.max(n))
      val idx = bv.indexOfSlice(slice)
      assert(idx == bv.toIndexedSeq.indexOfSlice(slice.toIndexedSeq))
      assert(bv.containsSlice(slice) == true)
      if (bv.nonEmpty) assert(bv.containsSlice(bv ++ bv) == false)
    }
  }

  test("endsWith") {
    forAll { (bv: BitVector, n0: Long) =>
      val n = if (bv.nonEmpty) (n0 % bv.size).abs else 0L
      val slice = bv.takeRight(n)
      assert(bv.endsWith(slice) == true)
      if (slice.nonEmpty) assert(bv.endsWith(~slice) == false)
    }
  }

  test("splice") {
    forAll { (x: BitVector, y: BitVector, n0: Long) =>
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0L
      assert(x.splice(n, BitVector.empty) == x)
      assert(x.splice(n, y) == (x.take(n) ++ y ++ x.drop(n)))
    }
  }

  test("patch") {
    forAll { (x: BitVector, y: BitVector, n0: Long) =>
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0L
      assert(x.patch(n, x.slice(n, n)) == x)
      assert(x.patch(n, y) == (x.take(n) ++ y ++ x.drop(n + y.size)))
    }
  }

  test("sizeLessThan") {
    forAll { (x: BitVector) =>
      assert(x.sizeLessThan(x.size + 1) == true)
      assert(x.sizeLessThan(x.size) == false)
    }
  }

  test("sizeGreaterThan") {
    forAll { (x: BitVector) =>
      assert((0 until x.size.toInt).forall(i => x.sizeGreaterThan(i.toLong)) == true)
      assert(x.sizeLessThan(x.size + 1) == true)
    }
  }

  test("byte conversions") {
    forAll { (n: Byte) =>
      assert(BitVector.fromByte(n).toByte() == n)
      assert(BitVector.fromByte(n).sliceToShort(0, 8) == n)
      assert(BitVector.fromByte(n).sliceToShort(4, 4) == BitVector.fromByte(n).drop(4).toByte())
    }
    assert(bin"11".toByte() == -1)
    assert(bin"11".toByte(signed = false) == 3)
    assert(BitVector.fromByte(3, 3) == bin"011")
  }

  test("short conversions") {
    forAll { (n: Short) =>
      assert(BitVector.fromShort(n).toShort() == n)
      assert(BitVector
        .fromShort(n, ordering = ByteOrdering.LittleEndian)
        .toShort(ordering = ByteOrdering.LittleEndian) == n)
      assert(BitVector.fromShort(n).sliceToShort(0, 16) == n)
      assert(BitVector.fromShort(n).sliceToShort(4, 12) == BitVector.fromShort(n).drop(4).toShort())
      assert(BitVector.fromShort(n).sliceToShort(4, 12, ordering = ByteOrdering.LittleEndian) ==
        BitVector.fromShort(n).drop(4).toShort(ordering = ByteOrdering.LittleEndian))
      if (n >= 0 && n < 16384) {
        assert(BitVector
          .fromShort(n, size = 15, ordering = ByteOrdering.BigEndian)
          .toShort(ordering = ByteOrdering.BigEndian) == n)
        assert(BitVector
          .fromShort(n, size = 15, ordering = ByteOrdering.LittleEndian)
          .toShort(ordering = ByteOrdering.LittleEndian) == n)
      }
    }
    assert(bin"11".toShort() == -1)
    assert(bin"11".toShort(signed = false) == 3)
  }

  test("int conversions") {
    forAll { (n: Int) =>
      assert(BitVector.fromInt(n).toInt() == n)
      assert(BitVector
        .fromInt(n, ordering = ByteOrdering.LittleEndian)
        .toInt(ordering = ByteOrdering.LittleEndian) == n)
      assert(BitVector.fromInt(n).sliceToInt(0, 32) == n)
      assert(BitVector.fromInt(n).sliceToInt(10, 22) == BitVector.fromInt(n).drop(10).toInt())
      assert(BitVector.fromInt(n).sliceToInt(10, 22, ordering = ByteOrdering.LittleEndian) ==
        BitVector.fromInt(n).drop(10).toInt(ordering = ByteOrdering.LittleEndian))
      if (n >= -16383 && n < 16384) {
        assert(BitVector
          .fromInt(n, size = 15, ordering = ByteOrdering.BigEndian)
          .toInt(ordering = ByteOrdering.BigEndian) == n)
        assert(BitVector
          .fromInt(n, size = 15, ordering = ByteOrdering.LittleEndian)
          .toInt(ordering = ByteOrdering.LittleEndian) == n)
      }
    }
  }

  test("long conversions") {
    forAll { (n: Long) =>
      assert(BitVector.fromLong(n).toLong() == n)
      assert(BitVector
        .fromLong(n, ordering = ByteOrdering.LittleEndian)
        .toLong(ordering = ByteOrdering.LittleEndian) == n)
      assert(BitVector.fromLong(n).sliceToLong(10, 54) == BitVector.fromLong(n).drop(10).toLong())
      assert(BitVector.fromLong(n).sliceToLong(10, 54, ordering = ByteOrdering.LittleEndian) ==
        BitVector.fromLong(n).drop(10).toLong(ordering = ByteOrdering.LittleEndian))
      if (n >= -16383 && n < 16384) {
        assert(BitVector
          .fromLong(n, size = 15, ordering = ByteOrdering.BigEndian)
          .toLong(ordering = ByteOrdering.BigEndian) == n)
        assert(BitVector
          .fromLong(n, size = 15, ordering = ByteOrdering.LittleEndian)
          .toLong(ordering = ByteOrdering.LittleEndian) == n)
      }
    }

    forAll(Gen.choose(Long.MinValue >> 8, Long.MinValue >> 16)) { (n: Long) =>
      assert(BitVector
        .fromLong(n, size = 56, ordering = ByteOrdering.BigEndian)
        .toLong(ordering = ByteOrdering.BigEndian) == n)
      assert(BitVector
        .fromLong(n, size = 56, ordering = ByteOrdering.LittleEndian)
        .toLong(ordering = ByteOrdering.LittleEndian) == n)
    }
  }

  test("UUID conversions") {
    // Valid conversions
    forAll { (u: UUID) =>
      assert(BitVector.fromUUID(u).toUUID == u)
    }
    // "Invalid" conversions
    val badlySizedBitVector: Gen[BitVector] = arbitraryBitVector.arbitrary.suchThat(_.length != 128)
    forAll(badlySizedBitVector) { badlySizedBitVector =>
      an[IllegalArgumentException] should be thrownBy { badlySizedBitVector.toUUID }
    }
  }

  test("buffering") {
    implicit val longs = Arbitrary(Gen.choose(-1L, 50L))

    def check(h: BitVector, xs: List[BitVector], delta: Long): Unit = {
      val unbuffered =
        BitVector.reduceBalanced(h :: xs)(_.size)(BitVector.Append(_, _))
      val buffered = xs.foldLeft(h)(_ ++ _)
      // sanity check for buffered
      assert((buffered.take(delta) ++ buffered.drop(delta)) == buffered)
      // checks for consistency:
      assert(buffered == unbuffered)
      // get
      (0L until unbuffered.size).foreach { i =>
        assert(buffered(i) == unbuffered(i))
      }
      // update
      val i = delta.min(unbuffered.size - 1).max(0)
      if (buffered.nonEmpty)
        assert(buffered.update(i, i % 2 == 0)(i) == unbuffered.update(i, i % 2 == 0)(i))
      // size
      assert(buffered.size == unbuffered.size)
      // take
      assert(buffered.take(delta) == unbuffered.take(delta))
      // drop
      assert(buffered.drop(delta) == unbuffered.drop(delta))
      ()
    }

    forAll { (h: BitVector, xs: List[BitVector], delta: Long) =>
      check(h, xs, delta)
      // "evil" case for buffering - chunks of increasing sizes
      val evil = (h :: xs).sortBy(_.size)
      check(evil.head, evil.tail, delta)
    }
  }

  test("concat") {
    forAll { (bvs: List[BitVector]) =>
      val c = BitVector.concat(bvs)
      assert(c.size == bvs.map(_.size).foldLeft(0L)(_ + _))
      bvs.headOption.foreach(h => c.startsWith(h))
      bvs.lastOption.foreach(l => c.endsWith(l))
    }
  }

  test("slice") {
    assert(hex"001122334455".bits.slice(8, 32) == hex"112233".bits)
    assert(hex"001122334455".bits.slice(-21, 32) == hex"00112233".bits)
    assert(hex"001122334455".bits.slice(-21, -5) == hex"".bits)
  }

  test("sliceToByte") {
    forAll { (x: BitVector, offset0: Long, sliceSize0: Int) =>
      val offset = if (x.nonEmpty) (offset0 % x.size).abs else 0
      val sliceSize = (sliceSize0 % 9).abs.min((x.size - offset).toInt)
      assert(x.sliceToByte(offset, sliceSize).toInt == x.drop(offset).take(sliceSize.toLong).toInt())
    }
  }

  test("highByte") {
    assert(BitVector.highByte.toBin == "11111111")
  }
}
