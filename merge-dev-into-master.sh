#!/bin/sh
git checkout master
git merge --no-commit dev
git checkout master -- bacmman-dl/src/main/resources/dockerfiles/ bacmman-distnet2d/src/main/resources/dockerfiles/ bacmman-gui/src/main/resources/dockerfiles/
git commit -m "merge dev"
