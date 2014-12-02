#!/bin/sh
#
# Builds and tests (for travis-ci.org)

set -e

PKGURL=$1
if [ -z "$PKGURL" ]; then
  echo "Usage: $0 package_url"
  exit 255
fi
PACKAGE=`basename $PKGURL .git`

# create and clean our temp build directory
mkdir -p target/spam
SPAM=`cd target/spam ; pwd`
rm -rf $SPAM/*
cd $SPAM

# download the spam script
rm -f spam
wget https://raw.githubusercontent.com/scaled/pacman/master/bin/spam
chmod a+rx spam
SPAMCMD="$SPAM/spam -d -Dscaled.meta=$SPAM"

# install/build the package
$SPAMCMD install $PKGURL

# then run our tests if we have any
TESTDIR=$SPAM/Packages/$PACKAGE/test
if [ -d $TESTDIR ]; then
  cd $TESTDIR
  $SPAMCMD run $PACKAGE#test org.junit.runner.JUnitCore \
    `find target/classes -name '*Test.class' | \
     sed 's:target/classes/::' | sed 's:.class::' | sed 's:/:.:g'`
else
  echo "No $TESTDIR. Not running tests."
fi
