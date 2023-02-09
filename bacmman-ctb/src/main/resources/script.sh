#!/bin/bash
# Prerequisities: Java 11, CUDA 11.0 to 11.2, CUDNN 8x
echo "$PWD"
CONFIG="{}"
java -cp dependency/*:bacmman-ctb-3.4.0.jar bacmman.ui.Run "../" $CONFIG