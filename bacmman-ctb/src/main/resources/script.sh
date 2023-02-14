#!/bin/bash
# Prerequisities: Java >=8, CUDA 11.0 to 11.2, CUDNN 8x
CONFIG=''
java -cp dependency/*:bacmman-ctb-3.4.0.jar bacmman.ui.Run "../Fluo-N2DH-SIM+/01" "$CONFIG" true