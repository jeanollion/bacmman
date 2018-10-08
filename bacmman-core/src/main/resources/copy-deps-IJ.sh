#!/bin/bash

fromDir=$1
toDir=$2

avoidList=( "3D_Viewer" "bio-formats" "formats" "fiji" "Fiji" "ij" "Image_5D" "imagej" "imagescience" "imageware" "imglib" "j3dcore" "j3dutils" "junit" "ome" "sciijava" "TrackMate" "Stitching" "vecmath" "loci" "JWIs" "VIB-lib" "batik" "commons-logging" "eventbus" "gentyref" "gluegen" "guava" "itextpdf" "jai_imageio" "jama" "javassist" "jcommon" "jdom2" "jfreechart" "jgoodies" "jgraph" "jhdf5" "joda-time" "jogl" "kryo" "legacy-imglib1" "mdbtools" "metadata-extractor" "metakit" "mines-jtk" "minlog" "mpicbg" "native-lib-loader" "netcdf" )

includeList=( "cglib" "commons-math" "json-simple" "mongo-java-driver" "morphium" )

echo "files from $fromDir will be inspected and copied into $toDir"

for fullfile in "$fromDir"/*; do
	copy=false 	
	filename="${fullfile##*/}"
	for include in "${includeList[@]}"; do
	if [[ $filename == $include* ]]; then
		#echo "file: $filename starts with: $include and thus will be included"
		copy=true
		break
	fi
	done
    #echo "copy file: $filename? $copy"
    if $copy ; then
        echo "file: $filename will be copied to dir: $toDir"
		cp $fullfile $toDir
    fi
done








for name in "${list[@]}"    
do   
        echo "name =" $name   
done

