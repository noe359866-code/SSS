# Cineflux · núcleo C++
# Compilación local de pruebas y sonda de diagnóstico (sin dependencias externas).
CXX      ?= c++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Wpedantic -Iinclude
BUILD    ?= build
SRC      := src/manifest.cpp src/profile.cpp src/stream.cpp src/ids.cpp src/skip.cpp src/catalog.cpp src/c_api.cpp
HDR      := $(wildcard include/cineflux/*.h include/cineflux/*.hpp)

.PHONY: all test probe clean

all: $(BUILD)/cineflux_tests $(BUILD)/cfx_probe

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/cineflux_tests: tests/test_core.cpp $(SRC) $(HDR) | $(BUILD)
	$(CXX) $(CXXFLAGS) -o $@ tests/test_core.cpp $(SRC)

$(BUILD)/cfx_probe: tools/cfx_probe.cpp $(SRC) $(HDR) | $(BUILD)
	$(CXX) $(CXXFLAGS) -o $@ tools/cfx_probe.cpp $(SRC)

test: $(BUILD)/cineflux_tests
	./$(BUILD)/cineflux_tests

probe: $(BUILD)/cfx_probe
	./$(BUILD)/cfx_probe

clean:
	rm -rf $(BUILD)
