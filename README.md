[![Codacy Badge](https://api.codacy.com/project/badge/Grade/83070da7805b4899820e285d2f7847b9)](https://www.codacy.com/manual/kai-morich/SimpleUsbTerminal?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=kai-morich/SimpleUsbTerminal&amp;utm_campaign=Badge_Grade)

# SimpleUsbTerminal-SILO_SAVERS

This Android app provides the Silo Savers group with a method of uploading the Sensor Data remotely

It supports USB to serial converters based on
- FTDI FT232, FT2232, ...
- Prolific PL2303
- Silabs CP2102, CP2105, ...
- Qinheng CH340, CH341

and devices implementing the USB CDC protocol like
- Arduino using ATmega32U4
- Digispark using V-USB software USB
- BBC micro:bit using ARM mbed DAPLink firmware
- Pi Pico
- ...

## Features

- Auto Connection to a Serial Device
- Uploading data to a Google Sheet including uploading battery logs and Pressure logs every so often
- If you run the Automate programs the device should function well automatically with little problem
- Log File uploads that are accessible from the phone's internal storage
- Use Automate to keep the app active and relaunch the app on boot up (Automate Flow Files are found in the repo under the folder called Automate)

## Credits

The app is a fork of the [Simple USB Terminal App](https://github.com/kai-morich/SimpleUsbTerminal)
The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library.
Credit to the Smart Stakes USDA team for the work on their app that we tried to replicate

## Motivation

Enable Silo Savers to upload data remotely from anywhere with an internet connection (wifi or data)

## Usage
In order to use and upload this app download Android Studio, plug the phone in with permissions to transfer files. Open This Android Studio Project and with the correct phone selected click the run/play button at the top
Phone must have developer mode enabled first and the ability to upload apps to it (setting found in the developer options section)

## To Dos
- Have a dedicated list of MUIs so that the app doesn't create fake MUIs and so that you can track the number of active sensors
- Possibly cut out the ESP32 code with the Phone Code do all of the UART parsing with the PIC18
