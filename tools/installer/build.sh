#!/bin/bash
# Build the Ruby Wars installer (dist/Ruby-Wars-Setup.exe) with the C# compiler that comes with Windows.
#   tools/installer/build.sh
set -e
cd "$(dirname "$0")/../.."
CSC=/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe
mkdir -p dist
python tools/installer/icon.py
"$CSC" -nologo -target:winexe -optimize+ -out:"$(cygpath -w dist/Ruby-Wars-Setup.exe)" \
	-win32icon:"$(cygpath -w tools/installer/ruby.ico)" \
	-r:System.Windows.Forms.dll -r:System.Drawing.dll -r:System.Web.Extensions.dll -r:System.IO.Compression.dll \
	-r:System.IO.Compression.FileSystem.dll -r:Microsoft.VisualBasic.dll -r:System.Core.dll \
	"$(cygpath -w tools/installer/RubyWarsSetup.cs)"
echo "Built dist/Ruby-Wars-Setup.exe"
