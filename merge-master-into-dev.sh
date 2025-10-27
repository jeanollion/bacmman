#!/bin/sh
git checkout dev
git merge --no-commit master
git checkout dev -- bacmman-dl/src/main/resources/dockerfiles/ bacmman-distnet2d/src/main/resources/dockerfiles/ bacmman-gui/src/main/resources/dockerfiles/
git commit -m "merge master"
