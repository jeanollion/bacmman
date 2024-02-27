/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.image.wrappers;

import bacmman.image.*;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Triplet;

import static bacmman.image.wrappers.IJImageWrapper.getImagePlus;

import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.display.projector.AbstractProjector2D;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.basictypeaccess.array.*;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.*;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Fraction;
import net.imglib2.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class ImgLib2ImageWrapper {
    static final Logger logger = LoggerFactory.getLogger(ImgLib2ImageWrapper.class);
    public static Image wrap(RandomAccessibleInterval img) {
        //ImagePlus ip = ImageJFunctions.wrap(img, "");
        //return IJImageWrapper.wrap(ip);
        Object[] primitiveArray = getPrimitiveArray(img);
        int sizeX = ( int ) img.dimension( 0 );
        //logger.debug("wrap: primitive array: z={} type: {}", primitiveArray.length, primitiveArray[0].getClass());
        Image res;
        if (primitiveArray instanceof byte[][]) {
            res = new ImageByte("", sizeX, (byte[][])primitiveArray);
        } else if (primitiveArray instanceof short[][]) {
            res = new ImageShort("", sizeX, (short[][])primitiveArray);
        } else if (primitiveArray instanceof int[][]) {
            res = new ImageInt("", sizeX, (int[][])primitiveArray);
        } else if (primitiveArray instanceof float[][]) {
            res = new ImageFloat("", sizeX, (float[][])primitiveArray);
        } else if (primitiveArray instanceof double[][]) {
            res = new ImageDouble("", sizeX, (double[][])primitiveArray);
        } else throw new IllegalArgumentException("unsupported type");
        res.setCalibration(getImageProperties(img));
        return res;
    }

    public static SimpleImageProperties getImageProperties(RandomAccessibleInterval img) {
        double scaleXY, scaleZ;
        if (img instanceof ImgPlus) {
            ImgPlus imgPlus = (ImgPlus)img;
            final int xIndex = imgPlus.dimensionIndex( Axes.X );
            scaleXY = imgPlus.averageScale( xIndex );
            final int zIndex = imgPlus.dimensionIndex( Axes.Z );
            scaleZ = zIndex>=0 ? imgPlus.averageScale( xIndex ) : 1;
        } else {
            scaleXY = 1;
            scaleZ = 1;
        }
        return new SimpleImageProperties(( int ) img.dimension( 0 ), img.numDimensions()>1 ? (int)img.dimension(1) : 1, img.numDimensions()>2 ? (int)img.dimension(2) : 1, scaleXY, scaleZ);
    }
    public static <T extends RealType<T> & NativeType<T>, T2 extends RealType<T2> & NativeType<T2>, A> A[] getPrimitiveArray(RandomAccessibleInterval<T> img) {
        if (img.numDimensions()>3) throw new IllegalArgumentException("Unsupported dimension number, should be <=3");
        Triplet<IntFunction<A[]>, T2, Converter<T, T2>> genAndCon = getGeneratorAndConvertor(img);
        IntFunction<A[]> generator = genAndCon.v1;
        Converter<T, T2> conv = genAndCon.v3;
        if (conv == null && img instanceof ArrayImg && img.numDimensions()<=2) {
            ArrayImg<T, ArrayDataAccess< ? >> aImg = (ArrayImg<T, ArrayDataAccess< ? >>) img;
            A[] res = generator.apply(1);
            res[0] = (A)aImg.update( null ).getCurrentStorageArray();
            return res;
        } else if (conv == null && img instanceof PlanarImg) {
            PlanarImg<T, ? extends ArrayDataAccess<?> > pImg = (PlanarImg<T, ?>) img;
            //logger.debug("planar image conversion: dimensions {} num slices: {}", img.dimensionsAsLongArray(), pImg.numSlices());
            return IntStream.range(0, pImg.numSlices()).mapToObj(pImg::getPlane).map(ArrayDataAccess::getCurrentStorageArray).toArray(generator);
        } else {
            //logger.debug("generic image conversion: dimensions: {} offset: {}", img.dimensionsAsLongArray(), img.minAsLongArray());
            return IntStream.range(0, img.numDimensions()>2 ? (int)img.dimension(2) : 1).mapToObj(z -> getSlice(img, z, genAndCon.v2, conv)).map(aImg -> aImg.update( null ).getCurrentStorageArray()).toArray(generator);
        }
    }

    private static <T, T2, A> Triplet<IntFunction<A[]>, T2, Converter< T, T2 >> getGeneratorAndConvertor(RandomAccessibleInterval<T> img) {
        T type = Util.getTypeFromInterval( img );
        if (UnsignedByteType.class.isInstance( type ) ) {
            return new Triplet<>(z -> (A[])new byte[z][], (T2)type, null);
        } else if (UnsignedShortType.class.isInstance( type ) ) {
            return new Triplet<>(z -> (A[])new short[z][], (T2)type, null);
        } else if (IntType.class.isInstance( type ) ) {
            return new Triplet<>(z -> (A[])new int[z][], (T2)type, null);
        } else if (FloatType.class.isInstance( type ) ) {
            return new Triplet<>(z -> (A[])new float[z][], (T2)type, null);
        } else if (DoubleType.class.isInstance( type ) ) {
            return new Triplet<>(z -> (A[])new double[z][], (T2)type, null);
        } else if (ByteType.class.isInstance( type )) {
            Converter<ByteType, FloatType> converter = (i, o) -> o.set( i.getRealFloat() );
            IntFunction<A[]> generator = z -> (A[])new float[z][];
            return new Triplet<>(generator, (T2)new FloatType(), (Converter< T, T2 >)converter);
        } else if (ShortType.class.isInstance( type )) {
            Converter<ByteType, FloatType> converter = (i, o) -> o.set( i.getRealFloat() );
            IntFunction<A[]> generator = z -> (A[])new float[z][];
            return new Triplet<>(generator, (T2)new FloatType(), (Converter< T, T2 >)converter);
        } else if (LongType.class.isInstance(type)) {
            Converter<LongType, DoubleType> converter = (i, o) -> o.set( i.getRealDouble() );
            IntFunction<A[]> generator = z -> (A[])new double[z][];
            return new Triplet<>(generator, (T2)new DoubleType(), (Converter< T, T2 >)converter);
        } else throw new IllegalArgumentException("unsupported type: "+type.getClass());
    }

    private static <T extends RealType<T> & NativeType<T>, T2 extends RealType<T2> & NativeType<T2>> ArrayImg< T2, ArrayDataAccess< ? > > getSlice(RandomAccessibleInterval< T > source, final int z, T2 type, Converter<T, T2> converter ) {
        if (converter == null) {
            Converter<T, T> conv = (i, o) -> o.set( i );
            converter = (Converter<T, T2>)conv;
        }
        if (type == null) type = (T2)Util.getTypeFromInterval( source );
        long[] dimensions = source.numDimensions()==1 ? new long[]{source.dimension(0)} : new long[]{source.dimension(0), source.dimension(1)};
        int[] sourceAxis = source.numDimensions()==1 ? new int[]{0} : new int[]{0, 1};
        final ArrayImg< T2,  ArrayDataAccess< ? > > img = (ArrayImg< T2, ArrayDataAccess< ? > >)new ArrayImgFactory<>( type ).create(dimensions);
        final IterableIntervalProjector1D2D projector = new IterableIntervalProjector1D2D<>(sourceAxis, source, img, converter );
        projector.setPosition(source.min(0), 0);
        if (source.numDimensions()>1) projector.setPosition(source.min(1), 1);
        if (source.numDimensions()>2) projector.setPosition( z, 2 );
        projector.map();
        return img;
    }
    
    public static <T extends RealType<T>> Img<T> getImage(Image image) {
        if (image.dimensions().length<=2) {
            if (image instanceof PrimitiveType.ByteType) {
                ArrayImg<UnsignedByteType, ByteArray> res = new ArrayImg<>(new ByteArray((byte[])image.getPixelArray()[0]), ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new UnsignedByteType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.ShortType) {
                ArrayImg<UnsignedShortType, ShortArray> res = new ArrayImg<>(new ShortArray((short[])image.getPixelArray()[0]), ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new UnsignedShortType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.IntType) {
                ArrayImg<IntType, IntArray> res = new ArrayImg<>(new IntArray((int[])image.getPixelArray()[0]), ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new IntType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.FloatType) {
                ArrayImg<FloatType, FloatArray> res = new ArrayImg<>(new FloatArray((float[])image.getPixelArray()[0]), ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new FloatType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.DoubleType) {
                ArrayImg<DoubleType, DoubleArray> res = new ArrayImg<>(new DoubleArray((double[])image.getPixelArray()[0]), ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new DoubleType( res ) );
                return (Img<T>)res;
            } else throw new IllegalArgumentException("Unsupported type");
        } else {
            if (image instanceof PrimitiveType.ByteType) {
                byte[][] pixelArray = (byte[][])image.getPixelArray();
                List<ByteArray> data = IntStream.range(0, image.sizeZ()).mapToObj(z -> new ByteArray(pixelArray[z])).collect(Collectors.toList());
                PlanarImg<UnsignedByteType, ByteArray> res = new PlanarImg<>(data, ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType(new UnsignedByteType(res));
                return (Img<T>) res;
            } else if (image instanceof PrimitiveType.ShortType) {
                short[][] pixelArray = (short[][])image.getPixelArray();
                List<ShortArray> data = IntStream.range(0, image.sizeZ()).mapToObj(z -> new ShortArray(pixelArray[z])).collect(Collectors.toList());
                PlanarImg<UnsignedShortType, ShortArray> res = new PlanarImg<>(data, ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new UnsignedShortType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.IntType) {
                int[][] pixelArray = (int[][])image.getPixelArray();
                List<IntArray> data = IntStream.range(0, image.sizeZ()).mapToObj(z -> new IntArray(pixelArray[z])).collect(Collectors.toList());
                PlanarImg<IntType, IntArray> res = new PlanarImg<>(data, ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new IntType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.FloatType) {
                float[][] pixelArray = (float[][])image.getPixelArray();
                List<FloatArray> data = IntStream.range(0, image.sizeZ()).mapToObj(z -> new FloatArray(pixelArray[z])).collect(Collectors.toList());
                PlanarImg<FloatType, FloatArray> res = new PlanarImg<>(data, ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new FloatType( res ) );
                return (Img<T>)res;
            } else if (image instanceof PrimitiveType.DoubleType) {
                double[][] pixelArray = (double[][])image.getPixelArray();
                List<DoubleArray> data = IntStream.range(0, image.sizeZ()).mapToObj(z -> new DoubleArray(pixelArray[z])).collect(Collectors.toList());
                PlanarImg<DoubleType, DoubleArray> res = new PlanarImg<>(data, ArrayUtil.toLong(image.dimensions()), new Fraction());
                res.setLinkedType( new DoubleType( res ) );
                return (Img<T>)res;
            } else throw new IllegalArgumentException("Unsupported type");
        }
        //return ImagePlusAdapter.wrapReal(IJImageWrapper.getImagePlus(image));
    }

    public static <T extends NativeType< T >> Img<T> createImage(final T type, final int[] dimensions) {
        return new ArrayImgFactory<T>(type).create(dimensions);
    }

    public enum INTERPOLATION {
        NEAREST(NearestNeighborInterpolatorFactory::new),
        NLINEAR(NLinearInterpolatorFactory::new),
        CLAMPING_NLINEAR(ClampingNLinearInterpolatorFactory::new),
        LANCZOS3(()->new LanczosInterpolatorFactory(3, false)),
        LANCZOS5(()->new LanczosInterpolatorFactory(5, false)),
        LANCZOS7(()->new LanczosInterpolatorFactory(7, false));
        private final Supplier<InterpolatorFactory> factory;
        INTERPOLATION(Supplier<InterpolatorFactory> factory) {
            this.factory=factory;
        }
        public InterpolatorFactory factory() {
            return factory.get();
        }
    }

    /*public static <T extends RealType<T>> Histogram1d<T> getHistogram(Img<T> img) {
        if (img.firstElement() instanceof IntegerType) {
            ((Img<IntegerType>)img)
        }
        //TODO : utiliser ops qui construit l'histogram!
        Histogram1d<IntegerType> hist=new Histogram1d<T>(new Integer1dBinMapper<T>(0,255,false)); 
        RandomAccess<LongType> raHist=hist.randomAccess();
        new MakeHistogram<T>((int)hist.getBinCount()).compute(input,hist);
    }*/
}
