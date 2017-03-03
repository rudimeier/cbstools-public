#!/bin/bash

# script to generate a python wrapper for CBS Tools code

exit_usage()
{
    echo "usage: $0 <install-dir>" >&2
    exit "$1"
}

test -n "$1" || exit_usage 1
target_dir="$1"

# 1.create a jar file with all the classes:
# jar cvf intensity.jar de/mpg/cbs/python/IntensityBackgroundEstimator.class de/mpg/cbs/utilities/Numerics.class de/mpg/cbs/libraries/ImageStatistics.class

# make sure we are in toplevel directory
cd "$(dirname "$0")/.." || exit
TOPDIR=$(pwd)
if ! grep -q "CBS High-Res Brain Processing Tools" README.md; then
    echo "error $0: seems we are not in cbstools-public toplevel directory" >&2
    exit 1
fi

# include ONLY the classes to manipulate via python (the other ones are handled within Java, which simplifies the dependencies
rm -f cbstools.jar
jar cvf cbstools.jar de/mpg/cbs/core/*/*.class

# all the used libraries must be included in the python distribution...
rm -f cbstools-lib.jar
jar cvf cbstools-lib.jar de/mpg/cbs/*/*.class

# 2.export target library path to python: important for isntallation
export PYTHONPATH="$target_dir:$PYTHONPATH"

# 3.compile with jcc:
# python -m jcc --jar intensity.jar --python intensity --build --classpath /home/pilou/Code/cbs/bazin --install --install-dir /home/pilou/Code/cbs/bazin/pylibs/
#CLASSPATH=/home/pilou/Code/github/cbstools:/home/pilou/Code/cbs/bazin/lib/commons-math3-3.5.jar:/home/pilou/Software/Mipav/jre/lib/ext/vecmath.jar:/home/pilou/Code/mipav:/home/pilou/Code/jist/src
#CLASSPATH=/home/pilou/Code/github/cbstools
#python -m jcc --jar cbstools.jar --include cbstools-lib.jar --include /home/pilou/Code/cbs/bazin/lib/commons-math3-3.5.jar --python cbstools --version 3.1.0 --build --classpath $CLASSPATH --maxheap 5000M --install --install-dir /home/pilou/Code/github/cbstools/python/

# no need for extra dependencies if all the code is included as jars (makes it independent from external installations, but requires to port or include all dependencies...)
python -m jcc --jar cbstools.jar --include cbstools-lib.jar --include lib/commons-math3-3.5.jar --include lib/Jama-mipav.jar --python cbstoolsjcc --version 3.1.0.1 --build --maxheap 5000M --install --install-dir "$target_dir"


